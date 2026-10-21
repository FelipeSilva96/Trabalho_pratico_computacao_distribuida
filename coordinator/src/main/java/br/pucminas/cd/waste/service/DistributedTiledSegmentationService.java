package br.pucminas.cd.waste.service;

import br.pucminas.cd.waste.clock.LamportClock;
import br.pucminas.cd.waste.grpc.WorkerClient;
import br.pucminas.cd.waste.grpc.WorkerPool;
import br.pucminas.cd.waste.image.ImageAssembler;
import br.pucminas.cd.waste.image.ImageTiler;
import br.pucminas.cd.waste.proto.SegmentRequest;
import br.pucminas.cd.waste.proto.SegmentResponse;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class DistributedTiledSegmentationService {
    private final WorkerPool workerPool;
    private final LamportClock clock;
    private final ExecutorService executor;

    public DistributedTiledSegmentationService(WorkerPool workerPool, LamportClock clock) {
        this.workerPool = workerPool;
        this.clock = clock;
        this.executor = Executors.newFixedThreadPool(Math.max(2, workerPool.size() * 2));
    }

    public record TileExecution(ImageTiler.Tile tile, byte[] resultPng,
                                String workerId, long workerLamport, long processingMs) {}

    public record Result(byte[] png, String requestId, int width, int height,
                         long totalTimeMs, long coordinatorLamport,
                         List<TileExecution> executions) {}

    public Result segment(MultipartFile file, int segments, double compactness,
                          int rows, int columns) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envie uma imagem.");
        }
        if (segments < 2 || segments > 5000 || !Double.isFinite(compactness) || compactness <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parametros do SLIC invalidos.");
        }
        if (rows < 1 || columns < 1 || rows > 8 || columns > 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Grade deve ter de 1 a 8 linhas/colunas.");
        }

        long start = System.nanoTime();
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(file.getBytes()));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nao foi possivel abrir a imagem.", e);
        }
        if (image == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Arquivo nao e uma imagem suportada.");
        }
        int width = image.getWidth();
        int height = image.getHeight();
        long pixels = (long) width * height;
        if (pixels > 16_000_000L || width / columns < 16 || height / rows < 16) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Use ate 16 milhoes de pixels e blocos com pelo menos 16x16 pixels.");
        }

        List<ImageTiler.Tile> tiles;
        try {
            tiles = ImageTiler.split(image, rows, columns);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Falha ao dividir imagem.", e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }

        String requestId = UUID.randomUUID().toString();
        List<CompletableFuture<TileExecution>> futures = tiles.stream()
                .map(tile -> CompletableFuture.supplyAsync(() ->
                        executeRemoteTile(requestId, tile,
                                Math.max(2, (int) Math.round((double) segments
                                        * tile.width() * tile.height() / pixels)),
                                compactness), executor))
                .toList();

        List<TileExecution> executions = new ArrayList<>();
        for (CompletableFuture<TileExecution> future : futures) {
            try {
                executions.add(future.join());
            } catch (CompletionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof IllegalArgumentException) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, cause.getMessage(), cause);
                }
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Falha ao segmentar pelo menos um bloco: " + cause.getMessage(), cause);
            }
        }

        List<ImageAssembler.Placement> placements = executions.stream()
                .map(r -> new ImageAssembler.Placement(r.tile(), r.resultPng()))
                .toList();

        byte[] assembled;
        try {
            assembled = ImageAssembler.assemble(width, height, placements);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Imagem retornada por worker e invalida.", e);
        }

        long totalTimeMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("[DISTRIBUTED] request=%s tiles=%d total=%dms%n",
                requestId, tiles.size(), totalTimeMs);
        return new Result(assembled, requestId, width, height, totalTimeMs,
                clock.get(), List.copyOf(executions));
    }

    private TileExecution executeRemoteTile(String requestId, ImageTiler.Tile tile,
                                            int segments, double compactness) {
        StringJoiner errors = new StringJoiner("; ");
        for (WorkerClient worker : workerPool.orderedFromNext()) {
            long sendTime = clock.tick();
            SegmentRequest request = SegmentRequest.newBuilder()
                    .setRequestId(requestId)
                    .setTileId(tile.id())
                    .setTilePng(ByteString.copyFrom(tile.png()))
                    .setTargetSegments(segments)
                    .setCompactness(compactness)
                    .setLogicalTime(sendTime)
                    .build();
            try {
                SegmentResponse response = worker.segment(request);
                long receiveTime = clock.receive(response.getLogicalTime());
                if (!"OK".equals(response.getStatus())
                        || !requestId.equals(response.getRequestId())
                        || !tile.id().equals(response.getTileId())
                        || response.getResultPng().isEmpty()) {
                    errors.add(worker.address() + ": resposta invalida");
                    continue;
                }
                System.out.printf("[TILE] request=%s tile=%s worker=%s process=%dms lamport=%d%n",
                        requestId, tile.id(), response.getWorkerId(),
                        response.getProcessingTimeMs(), receiveTime);
                return new TileExecution(tile, response.getResultPng().toByteArray(),
                        response.getWorkerId(), response.getLogicalTime(),
                        response.getProcessingTimeMs());
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) {
                    throw new IllegalArgumentException("Worker rejeitou bloco " + tile.id(), e);
                }
                errors.add(worker.address() + ": " + e.getStatus().getCode());
            }
        }
        throw new IllegalStateException("Nao foi possivel processar " + tile.id() + ": " + errors);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
