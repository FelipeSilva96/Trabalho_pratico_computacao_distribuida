package br.pucminas.cd.waste.dto;

public record ClassificationResult(
        String filename,
        String label,
        double confidence,
        String workerId,
        String status,
        long workerLogicalTime,
        long processingTimeMs,
        boolean demoMode
) {
}
