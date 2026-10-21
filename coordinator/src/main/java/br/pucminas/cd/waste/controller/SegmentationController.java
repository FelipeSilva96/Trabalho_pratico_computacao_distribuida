package br.pucminas.cd.waste.controller;

import br.pucminas.cd.waste.service.DistributedSegmentationService;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.*;

import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class SegmentationController {

    private final DistributedSegmentationService service;

    public SegmentationController(
            DistributedSegmentationService service
    ) {
        this.service = service;
    }

    @PostMapping(
        value = "/segment",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.IMAGE_PNG_VALUE
    )
    public ResponseEntity<byte[]> segment(

        @RequestParam("file")
        MultipartFile file,

        @RequestParam(
            value = "segments",
            defaultValue = "100"
        )
        int segments,

        @RequestParam(
            value = "compactness",
            defaultValue = "8.0"
        )
        double compactness

    ) {

        var result = service.segment(
            file,
            segments,
            compactness
        );

        HttpHeaders headers = new HttpHeaders();

        headers.setContentType(
            MediaType.IMAGE_PNG
        );

        headers.add(
            "X-Worker-Id",
            result.workerId()
        );

        headers.add(
            "X-Worker-Lamport",
            String.valueOf(result.workerLogicalTime())
        );

        headers.add(
            "X-Processing-Time-Ms",
            String.valueOf(result.processingTimeMs())
        );

        headers.add(
            "X-Coordinator-Lamport",
            String.valueOf(result.coordinatorLogicalTime())
        );

        return ResponseEntity
                .ok()
                .headers(headers)
                .body(result.image());
    }
}
