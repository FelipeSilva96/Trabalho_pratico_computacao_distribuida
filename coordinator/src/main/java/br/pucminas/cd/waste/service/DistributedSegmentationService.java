package br.pucminas.cd.waste.service;

import br.pucminas.cd.waste.clock.LamportClock;

import br.pucminas.cd.waste.grpc.WorkerClient;
import br.pucminas.cd.waste.grpc.WorkerPool;

import br.pucminas.cd.waste.proto.SegmentRequest;
import br.pucminas.cd.waste.proto.SegmentResponse;

import com.google.protobuf.ByteString;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.StringJoiner;
import java.util.UUID;

@Service
public class DistributedSegmentationService {

    private final WorkerPool workerPool;
    private final LamportClock clock;

    public DistributedSegmentationService(
            WorkerPool workerPool,
            LamportClock clock
    ) {
        this.workerPool = workerPool;
        this.clock = clock;
    }

    public SegmentationResult segment(
            MultipartFile file,
            int targetSegments,
            double compactness
    ) {

        // Verifica se o arquivo foi enviado
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Envie uma imagem válida."
            );
        }

        // Valida os parâmetros
        if (targetSegments < 2 || targetSegments > 5000) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "targetSegments deve estar entre 2 e 5000."
            );
        }

        if (
            !Double.isFinite(compactness)
            || compactness <= 0
        ) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "compactness deve ser positivo e finito."
            );
        }

        // Obtém os bytes da imagem
        byte[] imageBytes;

        try {
            imageBytes = file.getBytes();

        } catch (IOException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Não foi possível ler a imagem.",
                e
            );
        }

        // Identificador da requisição
        String requestId = UUID.randomUUID().toString();

        // Obtém os workers em ordem round-robin
        var workers = workerPool.orderedFromNext();

        StringJoiner failures = new StringJoiner(", ");

        // Tenta executar a tarefa remotamente
        for (WorkerClient worker : workers) {

            // Evento de envio da mensagem
            long sendTime = clock.tick();

            SegmentRequest request =
                    SegmentRequest.newBuilder()
                            .setRequestId(requestId)
                            .setTileId("imagem-inteira")
                            .setTilePng(
                                ByteString.copyFrom(imageBytes)
                            )
                            .setTargetSegments(targetSegments)
                            .setCompactness(compactness)
                            .setLogicalTime(sendTime)
                            .build();

            try {

                // Executa RPC
                SegmentResponse response = worker.segment(
                    request
                );

                // Atualiza o relógio ao receber resposta
                long receiveTime = clock.receive(
                    response.getLogicalTime()
                );

                // Verifica o resultado
                if (
                    !"OK".equals(response.getStatus())
                    || !requestId.equals(response.getRequestId())
                    || !"imagem-inteira".equals(response.getTileId())
                    || response.getResultPng().isEmpty()
                ) {
                    throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Resposta inválida do worker."
                    );
                }

                System.out.println(
                    "[COORDINATOR] "
                    + "Imagem processada por "
                    + response.getWorkerId()
                    + " | Lamport=" + receiveTime
                );

                return new SegmentationResult(
                    response.getResultPng().toByteArray(),
                    response.getWorkerId(),
                    response.getLogicalTime(),
                    response.getProcessingTimeMs(),
                    receiveTime
                );

            } catch (StatusRuntimeException e) {

                // Argumento inválido informado pelo worker
                if (
                    e.getStatus().getCode()
                    == Status.Code.INVALID_ARGUMENT
                ) {
                    throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "O worker rejeitou a imagem ou os parâmetros.",
                        e
                    );
                }

                // Registra falha da tentativa
                failures.add(
                    worker.address()
                    + ":"
                    + e.getStatus().getCode()
                );

                System.err.println(
                    "[COORDINATOR] Falha no worker "
                    + worker.address()
                    + ": "
                    + e.getStatus()
                );

                // Tenta o próximo worker
            }
        }

        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Nenhum worker conseguiu processar a imagem. "
                + failures
        );
    }

    public record SegmentationResult(
            byte[] image,
            String workerId,
            long workerLogicalTime,
            long processingTimeMs,
            long coordinatorLogicalTime
    ) {
    }
}
