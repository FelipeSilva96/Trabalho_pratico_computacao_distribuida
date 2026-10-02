package br.pucminas.cd.waste.dto;

public record WorkerHealth(
        String address,
        String workerId,
        String status,
        long logicalTime,
        boolean modelLoaded,
        boolean demoMode
) {
}
