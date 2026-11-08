package br.pucminas.cd.waste.controller;

import br.pucminas.cd.waste.replication.ReplicationCoordinator;
import br.pucminas.cd.waste.service.DistributedTiledSegmentationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoints NOVOS: preserva /api/segment-distributed inalterado.
 * A API HTTP fica vinculada a localhost no docker run, pois nao ha autenticacao.
 */
@RestController
@RequestMapping("/api")
public class ReplicationController {
    private final ReplicationCoordinator coordinator;
    private final DistributedTiledSegmentationService slic;

    public ReplicationController(ReplicationCoordinator coordinator,
                                 DistributedTiledSegmentationService slic) {
        this.coordinator = coordinator;
        this.slic = slic;
    }

    @PostMapping(value = "/replicas", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReplicationCoordinator.TransactionView replicatePng(@RequestParam("file") MultipartFile png)
            throws IOException {
        return coordinator.replicate(png.getBytes());
    }

    @PostMapping(value = "/segment-transactional", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> segmentAndReplicate(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "segments", defaultValue = "100") int segments,
            @RequestParam(value = "compactness", defaultValue = "8.0") double compactness,
            @RequestParam(value = "rows", defaultValue = "2") int rows,
            @RequestParam(value = "cols", defaultValue = "2") int cols) {
        var segmentation = slic.segment(file, segments, compactness, rows, cols);
        var tx = coordinator.replicate(segmentation.png());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transaction", tx);
        result.put("segmentationRequestId", segmentation.requestId());
        result.put("segmentationMs", segmentation.totalTimeMs());
        result.put("tiles", segmentation.executions().size());
        result.put("workers", segmentation.executions().stream().map(x -> x.workerId()).distinct().toList());
        return result;
    }

    @GetMapping("/transactions/{transactionId}")
    public ReplicationCoordinator.TransactionView status(@PathVariable String transactionId) {
        return coordinator.status(transactionId);
    }

    @PostMapping("/transactions/recover")
    public Map<String, String> recover() {
        coordinator.recover();
        return Map.of("status", "RECOVERY_ATTEMPTED");
    }

    @GetMapping(value = "/results/{resultId}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> read(@PathVariable String resultId) {
        byte[] png = coordinator.readPublished(resultId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.add("X-Result-Id", resultId);
        headers.add("X-SHA-256", ReplicationCoordinator.sha256(png));
        return ResponseEntity.ok().headers(headers).body(png);
    }
}
