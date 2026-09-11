package net.gommagomma.stardust.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.gommagomma.stardust.PhysicsConstants;
import net.gommagomma.stardust.SimulationParams;
import net.gommagomma.stardust.TestParams;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;

/**
 * Fixture condivise dai benchmark: un'unica implementazione del generatore di popolazione
 * "pesante" (massa planetesimale, non polvere).
 */
final class BenchmarkFixtures {

    private BenchmarkFixtures() {}

    static final double HEAVY_MASS_MIN = 1e23;
    static final double HEAVY_MASS_MAX = 1e25;

    /** Popolazione di massa planetesimale su un disco realistico, orbite circolari kepleriane. */
    static List<Particle> generateHeavyCloud(int n, long seed) {
        SimulationParams refParams = TestParams.defaults();
        Random rnd = new Random(seed);
        List<Particle> particles = new ArrayList<>(n);
        double rMin = refParams.diskInnerRadius;
        double rMax = refParams.diskOuterRadius;

        for (int i = 0; i < n; i++) {
            double r = Math.sqrt(rMin * rMin + rnd.nextDouble() * (rMax * rMax - rMin * rMin));
            double theta = rnd.nextDouble() * 2 * Math.PI;
            double x = r * Math.cos(theta);
            double y = r * Math.sin(theta);

            double v = Math.sqrt(PhysicsConstants.G * refParams.centralStarMass / r);
            double vx = -v * Math.sin(theta);
            double vy = v * Math.cos(theta);

            double mass = HEAVY_MASS_MIN + rnd.nextDouble() * (HEAVY_MASS_MAX - HEAVY_MASS_MIN);

            particles.add(new Particle(new Vector3D(x, y, 0), new Vector3D(vx, vy, 0), mass, 0.0, 3000.0));
        }
        return particles;
    }

    static List<Particle> deepCopy(List<Particle> particles) {
        List<Particle> copy = new ArrayList<>(particles.size());
        for (Particle p : particles) {
            copy.add(new Particle(p.getId(), p.getPosition(), p.getVelocity(), p.getMass(),
                    p.getCharge(), p.getDensity(), p.getInitialRadius(), p.getMergerCount()));
        }
        return copy;
    }
}