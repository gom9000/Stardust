package net.gommagomma.stardust.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.gommagomma.stardust.SimulationParams;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;

/**
 * Verifica l'invariante fondamentale del problema N-corpi quando le forze vengono accumulate
 * con lo schema a coppie usato da SimulationEngine.computeForcesSequential/Parallel.
 */
class NBodyForceAccumulationTest {

    private final SimulationParams params = new SimulationParams();
    private final Physics physics = new Physics(params);

    private static Particle particleAt(double x, double y, double z, double mass) {
        return new Particle(new Vector3D(x, y, z), new Vector3D(0, 0, 0), mass, 0.0, 3000.0);
    }

    /** Riproduce esattamente il pattern di SimulationEngine.computeForcesSequential. */
    private void applyPairwiseForces(List<Particle> particles) {
        for (Particle p : particles) {
            p.resetForce();
        }
        int n = particles.size();
        for (int i = 0; i < n; i++) {
            Particle p1 = particles.get(i);
            for (int j = i + 1; j < n; j++) {
                Particle p2 = particles.get(j);
                Vector3D fTotal = physics.calculateGravityAndElectrostaticForce(p1, p2);
                p1.addForce(fTotal);
                p2.addForce(fTotal.multiply(-1));
            }
        }
    }

    @Test
    void netForceOnSystem_isZero_forArbitraryNBodyConfiguration() {
        List<Particle> particles = new ArrayList<>();
        particles.add(particleAt(0, 0, 0, 5.0));
        particles.add(particleAt(12, -3, 0, 8.0));
        particles.add(particleAt(-7, 9, 2, 3.5));
        particles.add(particleAt(4, 4, -6, 11.0));
        particles.add(particleAt(-15, -2, 5, 1.5));
        particles.add(particleAt(9, -9, -9, 6.5));

        applyPairwiseForces(particles);

        Vector3D netForce = new Vector3D(0, 0, 0);
        double referenceScale = 0.0;
        for (Particle p : particles) {
            netForce = netForce.add(p.getForce());
            referenceScale = Math.max(referenceScale, p.getForce().magnitude());
        }

        assertTrue(netForce.magnitude() < referenceScale * 1e-9,
                "La somma delle forze nette su TUTTE le particelle deve annullarsi (residuo: "
                        + netForce.magnitude() + ", scala di riferimento: " + referenceScale + ")");
    }

    @Test
    void perParticleForce_matchesDirectSummationOverAllOthers() {
        List<Particle> particles = new ArrayList<>();
        particles.add(particleAt(0, 0, 0, 4.0));
        particles.add(particleAt(6, 0, 0, 7.0));
        particles.add(particleAt(0, 6, 0, 2.0));
        particles.add(particleAt(-3, -3, 3, 9.0));

        applyPairwiseForces(particles);

        for (Particle target : particles) {
            Vector3D expected = new Vector3D(0, 0, 0);
            for (Particle other : particles) {
                if (other == target) continue;
                expected = expected.add(physics.calculateGravityAndElectrostaticForce(target, other));
            }
            Vector3D actual = target.getForce();
            double scale = Math.max(1.0, expected.magnitude());
            assertEquals(expected.getX(), actual.getX(), scale * 1e-9,
                    "Forza x su particella id=" + target.getId());
            assertEquals(expected.getY(), actual.getY(), scale * 1e-9,
                    "Forza y su particella id=" + target.getId());
            assertEquals(expected.getZ(), actual.getZ(), scale * 1e-9,
                    "Forza z su particella id=" + target.getId());
        }
    }
}
