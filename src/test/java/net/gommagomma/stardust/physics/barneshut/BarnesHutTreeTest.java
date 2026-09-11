package net.gommagomma.stardust.physics.barneshut;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.gommagomma.stardust.SimulationParams;
import net.gommagomma.stardust.TestParams;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;
import net.gommagomma.stardust.physics.Physics;
import net.gommagomma.stardust.physics.gravity.GravityCalculator;

/**
 * Verifica il cuore dell'ottimizzazione N-corpi: BarnesHutTree.computeForce.
 *
 * Due proprietà indipendenti vengono testate:
 * 1) Con theta=0 il criterio di apertura non è MAI soddisfatto, quindi l'albero deve ricorrere
 *    sempre fino alle foglie: il risultato deve coincidere ESATTAMENTE con la sommatoria diretta
 *    O(N^2) via Physics.calculateGravityAndElectrostaticForce.
 * 2) Con theta piccolo ma > 0, un cluster di particelle molto vicine tra loro e lontano dal
 *    target deve essere approssimato come un'unica pseudo-particella nel proprio centro di
 *    massa: il risultato deve combaciare con la formula di softening di Plummer applicata
 *    a un corpo equivalente di massa = massa totale del cluster, posizionato nel baricentro.
 */
class BarnesHutTreeTest {

    private static final double REL_TOL = 1e-9;

    private final SimulationParams params = TestParams.defaults();
    private final Physics physics = new Physics(params);
    private final GravityCalculator gravityCalculator = new GravityCalculator(params);

    private static Particle particleAt(double x, double y, double z, double mass) {
        return new Particle(new Vector3D(x, y, z), new Vector3D(0, 0, 0), mass, 0.0, 3000.0);
    }

    private Vector3D directSumForce(Particle target, List<Particle> all) {
        Vector3D total = new Vector3D(0, 0, 0);
        for (Particle other : all) {
            if (other == target) continue;
            total = total.add(physics.calculateGravityAndElectrostaticForce(target, other));
        }
        return total;
    }

    private static void assertVectorClose(Vector3D expected, Vector3D actual, double relTol, String msg) {
        double scale = Math.max(1.0, expected.magnitude());
        assertEquals(expected.getX(), actual.getX(), scale * relTol, msg + " (x)");
        assertEquals(expected.getY(), actual.getY(), scale * relTol, msg + " (y)");
        assertEquals(expected.getZ(), actual.getZ(), scale * relTol, msg + " (z)");
    }

    private SimulationParams paramsWithTheta(double theta) {
        SimulationParams p = TestParams.defaults();
        p.barnesHutTheta = theta;
        return p;
    }

    @Test
    void theta0_matchesDirectSummation_exactly_forEveryParticle() {
        List<Particle> particles = new ArrayList<>();
        particles.add(particleAt(0, 0, 0, 5.0));
        particles.add(particleAt(37.0, -12.0, 4.0, 8.0));
        particles.add(particleAt(-20.0, 30.0, -5.0, 3.0));
        particles.add(particleAt(100.0, 0.0, 0.0, 12.0));
        particles.add(particleAt(-8.0, -8.0, 60.0, 2.0));

        SimulationParams theta0Params = paramsWithTheta(0.0);
        BarnesHutTree tree = new BarnesHutTree(particles, theta0Params, new Physics(theta0Params));

        for (Particle target : particles) {
            Vector3D bhForce = tree.computeForce(target);
            Vector3D directForce = directSumForce(target, particles);
            assertVectorClose(directForce, bhForce, 1e-6,
                    "A theta=0 la forza Barnes-Hut deve coincidere con la somma diretta O(N^2) su particella id=" + target.getId());
        }
    }

    @Test
    void distantTightCluster_isApproximatedByItsCenterOfMass() {
        Particle target = particleAt(0, 0, 0, 7.0);
        Particle clusterA = particleAt(1000.0, 0, 0, 3.0);
        Particle clusterB = particleAt(1001.0, 0, 0, 5.0);

        List<Particle> particles = List.of(target, clusterA, clusterB);
        SimulationParams smallThetaParams = paramsWithTheta(0.05);
        BarnesHutTree tree = new BarnesHutTree(particles, smallThetaParams, new Physics(smallThetaParams));

        Vector3D bhForce = tree.computeForce(target);

        double totalMass = clusterA.getMass() + clusterB.getMass();
        double comX = (clusterA.getPosition().getX() * clusterA.getMass()
                + clusterB.getPosition().getX() * clusterB.getMass()) / totalMass;

        Particle virtualComParticle = particleAt(comX, 0, 0, totalMass);
        // La formula di approssimazione del nodo è ESATTAMENTE calculatePlummerGravity con
        // massa=massa totale del cluster e posizione=baricentro (stesso softening di smallThetaParams).
        Vector3D expectedForce = new GravityCalculator(smallThetaParams).calculatePlummerGravity(target, virtualComParticle);

        assertVectorClose(expectedForce, bhForce, 1e-6,
                "Un cluster stretto e lontano deve essere approssimato dalla sua pseudo-particella nel baricentro");
    }

    @Test
    void netForceOverAllParticles_isZero_atTheta0() {
        List<Particle> particles = new ArrayList<>();
        particles.add(particleAt(0, 0, 0, 5.0));
        particles.add(particleAt(10, 0, 0, 8.0));
        particles.add(particleAt(0, 10, 0, 3.0));
        particles.add(particleAt(-10, -5, 2, 12.0));
        particles.add(particleAt(4, -4, -4, 6.0));

        SimulationParams theta0Params = paramsWithTheta(0.0);
        BarnesHutTree tree = new BarnesHutTree(particles, theta0Params, new Physics(theta0Params));

        Vector3D netForce = new Vector3D(0, 0, 0);
        for (Particle p : particles) {
            netForce = netForce.add(tree.computeForce(p));
        }

        double scale = tree.computeForce(particles.get(0)).magnitude();
        assertTrue(netForce.magnitude() < scale * 1e-6,
                "A theta=0 la somma delle forze nette sull'intero sistema deve annullarsi (residuo: "
                        + netForce.magnitude() + ", scala: " + scale + ")");
    }

    @Test
    void largerTheta_neverDivergesWildlyFromDirectSummation_forWellSeparatedClusters() {
        List<Particle> particles = new ArrayList<>();
        particles.add(particleAt(0, 0, 0, 4.0));
        particles.add(particleAt(2, 1, 0, 6.0));
        particles.add(particleAt(-1, 2, 1, 3.0));
        particles.add(particleAt(500, 0, 0, 9.0));
        particles.add(particleAt(502, -1, 1, 5.0));
        particles.add(particleAt(499, 2, -1, 7.0));

        BarnesHutTree tree = new BarnesHutTree(particles, params, physics); // theta di default di SimulationParams()
        Particle target = particles.get(0);

        Vector3D bhForce = tree.computeForce(target);
        Vector3D directForce = directSumForce(target, particles);

        double relError = bhForce.subtract(directForce).magnitude() / directForce.magnitude();
        assertTrue(relError < 0.05,
                "Con theta di default e cluster ben separati, l'errore relativo di Barnes-Hut deve restare sotto il 5% (osservato: "
                        + (relError * 100) + "%)");
    }
}
