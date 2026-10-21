import os
import time
import math

from concurrent import futures

import grpc

import classifier_pb2
import classifier_pb2_grpc

from classifier import WasteClassifier
from segmenter import segmentar_imagem
from lamport import LamportClock


WORKER_ID = os.getenv("WORKER_ID", "worker")

GRPC_PORT = int(
    os.getenv("GRPC_PORT", "50051")
)

GRPC_THREADS = int(
    os.getenv("GRPC_THREADS", "4")
)


# Relógio lógico do worker
clock = LamportClock()

# Classificador original
classifier = WasteClassifier()


class ClassifierService(
    classifier_pb2_grpc.ClassifierServiceServicer
):

    # ===================================
    # CLASSIFICAÇÃO ORIGINAL
    # ===================================

    def Classify(self, request, context):

        clock.receive(request.logical_time)

        start = time.perf_counter()

        try:

            prediction = classifier.predict(
                request.image
            )

        except Exception as exc:

            prediction = {
                "label": "",
                "confidence": 0.0,
                "status": (
                    f"INVALID_IMAGE:"
                    f"{type(exc).__name__}"
                ),
                "demo_mode": classifier.demo_mode,
            }

        elapsed_ms = int(
            (time.perf_counter() - start) * 1000
        )

        response_time = clock.tick()

        return classifier_pb2.ClassifyResponse(
            request_id=request.request_id,
            filename=request.filename,
            label=prediction["label"],
            confidence=prediction["confidence"],
            worker_id=WORKER_ID,
            status=prediction["status"],
            logical_time=response_time,
            processing_time_ms=elapsed_ms,
            demo_mode=prediction["demo_mode"],
        )

    # ===================================
    # NOVA OPERAÇÃO: SEGMENTAÇÃO SLIC
    # ===================================

    def Segment(self, request, context):

        # Atualiza o relógio lógico
        # ao receber a mensagem
        receive_time = clock.receive(
            request.logical_time
        )

        print(
            f"[{WORKER_ID}] "
            f"Recebida tarefa {request.tile_id} "
            f"| Lamport={receive_time}",
            flush=True
        )

        # Validação dos parâmetros
        if not request.tile_png:

            context.abort(
                grpc.StatusCode.INVALID_ARGUMENT,
                "Imagem não informada"
            )

        if not 2 <= request.target_segments <= 5000:

            context.abort(
                grpc.StatusCode.INVALID_ARGUMENT,
                "target_segments deve estar entre 2 e 5000"
            )

        if (
            not math.isfinite(request.compactness)
            or request.compactness <= 0
        ):

            context.abort(
                grpc.StatusCode.INVALID_ARGUMENT,
                "compactness deve ser positivo e finito"
            )

        start = time.perf_counter()

        try:

            # Executa o SLIC
            imagem_segmentada = segmentar_imagem(
                request.tile_png,
                n_segments=request.target_segments,
                compactness=request.compactness
            )

        except (OSError, ValueError) as exc:

            context.abort(
                grpc.StatusCode.INVALID_ARGUMENT,
                f"Imagem inválida: {type(exc).__name__}"
            )

        except Exception as exc:

            print(
                f"[{WORKER_ID}] "
                f"Erro interno ao segmentar: {exc}",
                flush=True
            )

            context.abort(
                grpc.StatusCode.INTERNAL,
                "Falha interna na segmentação"
            )

        # Tempo de processamento
        elapsed_ms = int(
            (time.perf_counter() - start) * 1000
        )

        # Evento de envio da resposta
        response_time = clock.tick()

        print(
            f"[{WORKER_ID}] "
            f"Tarefa {request.tile_id} concluída "
            f"| Tempo={elapsed_ms}ms "
            f"| Lamport={response_time}",
            flush=True
        )

        # Retorna o resultado
        return classifier_pb2.SegmentResponse(

            request_id=request.request_id,

            tile_id=request.tile_id,

            result_png=imagem_segmentada,

            worker_id=WORKER_ID,

            status="OK",

            logical_time=response_time,

            processing_time_ms=elapsed_ms

        )

    # ===================================
    # HEALTH CHECK
    # ===================================

    def Health(self, request, context):

        clock.receive(request.logical_time)

        response_time = clock.tick()

        return classifier_pb2.HealthResponse(

            worker_id=WORKER_ID,

            status="UP",

            logical_time=response_time,

            model_loaded=classifier.model_loaded,

            demo_mode=classifier.demo_mode

        )


def serve():

    server = grpc.server(

        futures.ThreadPoolExecutor(
            max_workers=GRPC_THREADS
        ),

        options=[

            (
                "grpc.max_receive_message_length",
                16 * 1024 * 1024
            ),

            (
                "grpc.max_send_message_length",
                16 * 1024 * 1024
            )

        ]

    )

    classifier_pb2_grpc.add_ClassifierServiceServicer_to_server(
        ClassifierService(),
        server
    )

    server.add_insecure_port(
        f"[::]:{GRPC_PORT}"
    )

    server.start()

    print(
        f"{WORKER_ID} iniciado "
        f"na porta gRPC {GRPC_PORT}",
        flush=True
    )

    server.wait_for_termination()


if __name__ == "__main__":
    serve()