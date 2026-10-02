package br.pucminas.cd.waste.dto;

import java.util.List;

public record BatchClassificationResponse(
        String requestId,
        int totalImages,
        long totalTimeMs,
        long coordinatorLogicalTime,
        List<ClassificationResult> results
) {
}
