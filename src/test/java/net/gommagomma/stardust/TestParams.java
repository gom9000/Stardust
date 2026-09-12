package net.gommagomma.stardust;

/**
 * Fabbrica di SimulationParams "di comodo" per i test: nessun filesystem coinvolto, valori scelti
 * per essere leggibili e riproducibili nei test, non necessariamente identici a parameters.txt
 * (che può cambiare in produzione senza che i test debbano seguirlo).
 *
 * Questa classe è l'UNICA fonte di questi numeri per i test -- se un test ha bisogno di un valore
 * diverso, lo sovrascrive esplicitamente sull'istanza ottenuta da defaults(), non lo duplica qui.
 */
public final class TestParams {

    private TestParams() {}

    public static SimulationParams defaults() {
        SimulationParams params = new SimulationParams();

        params.n = 15000;
        params.initialParticleMassMin = 2e19;
        params.initialParticleMassMax = 2e21;
        params.diskInnerRadius = 0.3 * PhysicsConstants.AU;
        params.diskOuterRadius = 0.7 * PhysicsConstants.AU;
        params.initialVelocityDispersion = 0.005;
        params.initialParticleDensity = 100.0;

        params.dt = 300.0;
        params.softening = 1.0;
        params.useParallelForces = true;
        params.useBarnesHut = true;
        params.barnesHutTheta = 0.6;
        params.barnesHutThreshold = 400;
        params.parallelForcesThreshold = 100;

        params.hillCaptureFraction = 0.20;
        params.hillAmplification = 3.0;
        params.gravitationalCaptureMultiplier = 1.0;
        params.mergeVelocityFloor = 2.5;
        params.fragmentationMultiplier = 2.0;

        params.dragReferenceDensity = 3000.0;
        params.gasDensityBase = 1.4e-9;

        return params;
    }
}
