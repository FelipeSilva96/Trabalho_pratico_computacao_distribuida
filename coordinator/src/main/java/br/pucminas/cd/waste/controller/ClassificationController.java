package br.pucminas.cd.waste.controller;

import br.pucminas.cd.waste.dto.BatchClassificationResponse;
import br.pucminas.cd.waste.service.DistributedClassificationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ClassificationController {
    private final DistributedClassificationService service;

    public ClassificationController(DistributedClassificationService service) {
        this.service = service;
    }

    @PostMapping(
            value = "/classify",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public BatchClassificationResponse classify(@RequestParam("files") List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Envie pelo menos uma imagem");
        }

        return service.classify(files);
    }

    @GetMapping("/workers")
    public Object workers() {
        return service.workersHealth();
    }
}
