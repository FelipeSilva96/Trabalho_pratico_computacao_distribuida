import hashlib
import math
import os
import time
from concurrent import futures

import grpc

import classifier_pb2
import classifier_pb2_grpc
from classifier import WasteClassifier
from segmenter import segmentar_imagem
from lamport import LamportClock
from replica_store import ReplicaConflict, ReplicaCorrupted
from transaction_participant import TwoPhaseParticipant, TransactionConflict, TransactionCorrupted

WORKER_ID = os.getenv("WORKER_ID", "worker")
GRPC_PORT = int(os.getenv("GRPC_PORT", "50051"))
GRPC_THREADS = int(os.getenv("GRPC_THREADS", "4"))
REPLICA_DIR = os.getenv("REPLICA_DIR", "/app/data")

clock = LamportClock()
classifier = WasteClassifier()
participant = TwoPhaseParticipant(REPLICA_DIR)


class ClassifierService(classifier_pb2_grpc.ClassifierServiceServicer):
    def Classify(self, request, context):
        clock.receive(request.logical_time)
        start = time.perf_counter()
        try:
            prediction = classifier.predict(request.image)
        except Exception as exc:
            prediction = {
                "label": "", "confidence": 0.0,
                "status": f"INVALID_IMAGE:{type(exc).__name__}",
                "demo_mode": classifier.demo_mode,
            }
        elapsed_ms = int((time.perf_counter() - start) * 1000)
        return classifier_pb2.ClassifyResponse(
            request_id=request.request_id, filename=request.filename,
            label=prediction["label"], confidence=prediction["confidence"],
            worker_id=WORKER_ID, status=prediction["status"],
            logical_time=clock.tick(), processing_time_ms=elapsed_ms,
            demo_mode=prediction["demo_mode"],
        )

    def Segment(self, request, context):
        recv_time = clock.receive(request.logical_time)
        print(f"[{WORKER_ID}] Recebida tarefa {request.tile_id} | Lamport={recv_time}", flush=True)
        if not request.tile_png:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, "Imagem nao informada")
        if not 2 <= request.target_segments <= 5000:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, "target_segments deve estar entre 2 e 5000")
        if not math.isfinite(request.compactness) or request.compactness <= 0:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, "compactness deve ser positivo e finito")
        start = time.perf_counter()
        try:
            image = segmentar_imagem(request.tile_png, n_segments=request.target_segments,
                                     compactness=request.compactness)
        except (OSError, ValueError) as exc:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, f"Imagem invalida: {type(exc).__name__}")
        except Exception as exc:
            print(f"[{WORKER_ID}] Erro interno na segmentacao: {exc}", flush=True)
            context.abort(grpc.StatusCode.INTERNAL, "Falha interna na segmentacao")
        elapsed_ms = int((time.perf_counter() - start) * 1000)
        response_time = clock.tick()
        print(f"[{WORKER_ID}] Tarefa {request.tile_id} concluida | Tempo={elapsed_ms}ms | Lamport={response_time}",flush=True)
        return classifier_pb2.SegmentResponse(
            request_id=request.request_id, tile_id=request.tile_id,
            result_png=image, worker_id=WORKER_ID, status="OK",
            logical_time=response_time, processing_time_ms=elapsed_ms,
        )

    def Health(self, request, context):
        clock.receive(request.logical_time)
        return classifier_pb2.HealthResponse(
            worker_id=WORKER_ID, status="UP", logical_time=clock.tick(),
            model_loaded=classifier.model_loaded, demo_mode=classifier.demo_mode,
        )

    # Participante local de transacao: decide somente quando o coordenador
    # enviar COMMIT/ABORT. Nenhuma decisao global e tomada neste servidor.
    @staticmethod
    def _call(context, operation):
        try:
            return operation()
        except KeyError:
            context.abort(grpc.StatusCode.NOT_FOUND, "Transacao/resultado nao encontrado")
        except ValueError as exc:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(exc))
        except (ReplicaConflict, TransactionConflict) as exc:
            context.abort(grpc.StatusCode.FAILED_PRECONDITION, str(exc))
        except (ReplicaCorrupted, TransactionCorrupted) as exc:
            context.abort(grpc.StatusCode.DATA_LOSS, str(exc))
        except Exception as exc:
            print(f"[{WORKER_ID}] Erro no participante: {type(exc).__name__}: {exc}",flush=True)
            context.abort(grpc.StatusCode.INTERNAL, "Falha interna na replica")

    def PrepareReplica(self, request, context):
        clock.receive(request.logical_time)
        state = self._call(context, lambda: participant.prepare(
            request.transaction_id, request.result_id, request.image_png, request.sha256))
        logical = clock.tick()
        print(f"[{WORKER_ID}] PREPARE tx={request.transaction_id} state={state} lamport={logical}",flush=True)
        return classifier_pb2.ReplicaOperationResponse(
            transaction_id=request.transaction_id,worker_id=WORKER_ID,state=state,logical_time=logical)

    def CommitReplica(self, request, context):
        clock.receive(request.logical_time)
        state = self._call(context,lambda:participant.commit(request.transaction_id))
        logical=clock.tick()
        print(f"[{WORKER_ID}] COMMIT tx={request.transaction_id} state={state} lamport={logical}",flush=True)
        return classifier_pb2.ReplicaOperationResponse(
            transaction_id=request.transaction_id,worker_id=WORKER_ID,state=state,logical_time=logical)

    def AbortReplica(self, request, context):
        clock.receive(request.logical_time)
        state=self._call(context,lambda:participant.abort(request.transaction_id))
        logical=clock.tick()
        print(f"[{WORKER_ID}] ABORT tx={request.transaction_id} state={state} lamport={logical}",flush=True)
        return classifier_pb2.ReplicaOperationResponse(
            transaction_id=request.transaction_id,worker_id=WORKER_ID,state=state,logical_time=logical)

    def ReplicaStatus(self, request, context):
        clock.receive(request.logical_time)
        data=self._call(context,lambda:participant.status(request.transaction_id))
        return classifier_pb2.ReplicaStatusResponse(
            transaction_id=request.transaction_id,result_id=data["result_id"],
            sha256=data["sha256"],state=data["state"],worker_id=WORKER_ID,
            logical_time=clock.tick())

    def ReadReplica(self, request, context):
        clock.receive(request.logical_time)
        data=self._call(context,lambda:participant.read_committed(request.result_id))
        return classifier_pb2.ReadReplicaResponse(
            result_id=request.result_id,image_png=data,
            sha256=hashlib.sha256(data).hexdigest(),worker_id=WORKER_ID,
            logical_time=clock.tick())


def serve():
    server=grpc.server(futures.ThreadPoolExecutor(max_workers=GRPC_THREADS),
        options=[("grpc.max_receive_message_length",16*1024*1024),
                 ("grpc.max_send_message_length",16*1024*1024)])
    classifier_pb2_grpc.add_ClassifierServiceServicer_to_server(ClassifierService(),server)
    server.add_insecure_port(f"[::]:{GRPC_PORT}")
    server.start()
    print(f"{WORKER_ID} iniciado na porta gRPC {GRPC_PORT}",flush=True)
    server.wait_for_termination()


if __name__ == "__main__":
    serve()
