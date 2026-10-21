import os
import time
from concurrent import futures

import grpc

import classifier_pb2
import classifier_pb2_grpc
from classifier import WasteClassifier
from lamport import LamportClock


WORKER_ID = os.getenv("WORKER_ID", "worker")
GRPC_PORT = int(os.getenv("GRPC_PORT", "50051"))
GRPC_THREADS = int(os.getenv("GRPC_THREADS", "4"))

clock = LamportClock()
classifier = WasteClassifier()


class ClassifierService(classifier_pb2_grpc.ClassifierServiceServicer):
    def Classify(self, request, context):
        clock.receive(request.logical_time)
        start = time.perf_counter()

        try:
            prediction = classifier.predict(request.image)
        except Exception as exc:
            prediction = {
                "label": "",
                "confidence": 0.0,
                "status": f"INVALID_IMAGE:{type(exc).__name__}",
                "demo_mode": classifier.demo_mode,
            }

        elapsed_ms = int((time.perf_counter() - start) * 1000)
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

    def Health(self, request, context):
        clock.receive(request.logical_time)
        response_time = clock.tick()

        return classifier_pb2.HealthResponse(
            worker_id=WORKER_ID,
            status="UP",
            logical_time=response_time,
            model_loaded=classifier.model_loaded,
            demo_mode=classifier.demo_mode,
        )


def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=GRPC_THREADS))
    classifier_pb2_grpc.add_ClassifierServiceServicer_to_server(
        ClassifierService(),
        server,
    )
    server.add_insecure_port(f"[::]:{GRPC_PORT}")
    server.start()

    print(
        f"{WORKER_ID} iniciado em gRPC :{GRPC_PORT} | "
        f"model_loaded={classifier.model_loaded} | "
        f"demo_mode={classifier.demo_mode}",
        flush=True,
    )

    server.wait_for_termination()


if __name__ == "__main__":
    serve()
