package br.pucminas.cd.waste.controller;

import br.pucminas.cd.waste.service.DistributedTiledSegmentationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class DistributedTiledSegmentationController {
    private final DistributedTiledSegmentationService service;

    public DistributedTiledSegmentationController(DistributedTiledSegmentationService service) {
        this.service = service;
    }

    @PostMapping(value = "/segment-distributed",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> segment(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "segments", defaultValue = "100") int segments,
            @RequestParam(value = "compactness", defaultValue = "8.0") double compactness,
            @RequestParam(value = "rows", defaultValue = "2") int rows,
            @RequestParam(value = "cols", defaultValue = "2") int columns) {

        var result = service.segment(file, segments, compactness, rows, columns);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.add("X-Request-Id", result.requestId());
        headers.add("X-Tiles", String.valueOf(result.executions().size()));
        headers.add("X-Total-Time-Ms", String.valueOf(result.totalTimeMs()));
        headers.add("X-Coordinator-Lamport", String.valueOf(result.coordinatorLamport()));
        headers.add("X-Worker-Ids", result.executions().stream()
                .map(DistributedTiledSegmentationService.TileExecution::workerId)
                .distinct().collect(Collectors.joining(",")));
        headers.add("X-Tile-Workers", result.executions().stream()
                .map(t -> t.tile().id() + "=" + t.workerId())
                .collect(Collectors.joining(",")));
        return ResponseEntity.ok().headers(headers).body(result.png());
    }
}
