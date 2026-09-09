package net.gommagomma.stardust.physics.barneshut;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.gommagomma.stardust.SimulationConfig;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;
import net.gommagomma.stardust.physics.Physics;
import net.gommagomma.stardust.physics.gravity.GravityCalculator;

/**
 * Verifica il cuore dell'ottimizzazione N-corpi: BarnesHutTree.computeForce.
 *
 * Due proprietà indipendenti vengono testate:
 * 1) Con theta=0 il criterio di apertura (size²/distSq < thetaSq) non è MAI soddisfatto
 *    (thetaSq=0), quindi l'albero deve ricorrere sempre fino alle foglie: il risultato
 *    deve coincidere ESATTAMENTE con la sommatoria diretta O(N²) via Physics.calculateGravityAndElectrostaticForce.
 * 2) Con theta piccolo ma > 0, un cluster di particelle molto vicine tra loro e lontano dal
 *    target deve essere approssimato come un'unica pseudo-particella nel proprio centro di
 *    massa: il risultato deve combaciare con la formula di softening di Plummer applicata
 *    a un corpo equivalente di massa = massa totale del cluster, posizionato nel baricentro
 *    (è letteralmente la stessa formula usata internamente dal nodo per l'approssimazione).
 */
class BarnesHutTreeTest {

    private static final double REL_TOL = 1e-9;

    private static Particle particleAt(double x, double y, double z, double mass) {
        return new Particle(new Vector3D(x, y, z), new Vector3D(0, 0, 0), mass, 0.0, 3000.0);
    }

    private static Vector3D directSumForce(Particle target, List<Particle> all) {
        Vector3D total = new Vector3D(0, 0, 0);
        for (Particle other : all) {
            if (other == target) continue;
            total = total.add(Physics.calculateGravityAndElectrostaticForce(target, other));
        }
        return total;
    }

    private static void assertVectorClose(Vector3D expected, Vector3D actual, double relTol, String msg) {
        double scale = Math.max(1.0, expected.magnitude());
        assertEquals(expected.getX(), actual.getX(), scale * relTol, msg + " (x)");
        assertEquals(expected.getY(), actual.getY(), scale * relTol, msg + " (y)");
        assertEquals(expected.getZ(), actual.getZ(), scale * relTol, msg + " (z)");
    }

    @Test
    void theta0_matchesDirectSummation_exactly_forEveryParticle() {
        List<Particle> particles = new ArrayList<>();
        particles.add(particleAt(0, 0, 0, 5.0));
        particles.add(particleAt(37.0, -12.0, 4.0, 8.0));
        particles.add(particleAt(-20.0, 30.0, -5.0, 3.0));
        particles.add(particleAt(100.0, 0.0, 0.0, 12.0));
        particles.add(particleAt(-8.0, -8.0, 60.0, 2.0));

        BarnesHutTree tree = new BarnesHutTree(particles, 0.0);

        for (Particle target : particles) {
            Vector3D bhForce = tree.computeForce(target);
            Vector3D directForce = directSumForce(target, particles);
            assertVectorClose(directForce, bhForce, 1e-6,
                    "A theta=0 la forza Barnes-Hut deve coincidere con la somma diretta O(N^2) su particella id=" + target.getId());
        }
    }

    @Test
    void distantTightCluster_isApproximatedByItsCenterOfMass() {
        // Due particelle molto vicine (1 m di separazione) rispetto alla distanza dal target (1000 m):
        // con theta piccolo, il nodo che le contiene entrambe deve essere trattato come un'unica
        // pseudo-particella nel baricentro, con la STESSA formula (Plummer-softened) usata dal nodo.
        Particle target = particleAt(0, 0, 0, 7.0);
        Particle clusterA = particleAt(1000.0, 0, 0, 3.0);
        Particle clusterB = particleAt(1001.0, 0, 0, 5.0);

        List<Particle> particles = List.of(target, clusterA, clusterB);
        double theta = 0.05; // piccolo: forza l'albero a scendere in profondità prima di approssimare
        BarnesHutTree tree = new BarnesHutTree(particles, theta);

        Vector3D bhForce = tree.computeForce(target);

        // Baricentro atteso e massa totale del cluster (calcolati indipendentemente dal codice sotto test).
        double totalMass = clusterA.getMass() + clusterB.getMass();
        double comX = (clusterA.getPosition().getX() * clusterA.getMass()
                + clusterB.getPosition().getX() * clusterB.getMass()) / totalMass;

        Particle virtualComParticle = particleAt(comX, 0, 0, totalMass);
        // La formula di approssimazione del nodo è ESATTAMENTE calculatePlummerGravity con
        // massa=massa totale del cluster e posizione=baricentro (stesso SOFTENING globale).
        Vector3D expectedForce = GravityCalculator.calculatePlummerGravity(target, virtualComParticle);

        assertVectorClose(expectedForce, bhForce, 1e-6,
                "Un cluster stretto e lontano deve essere approssimato dalla sua pseudo-particella nel baricentro");
    }

    @Test
    void netForceOverAllParticles_isZero_atTheta0() {
        // Corollario della terza legge di Newton applicata all'intero sistema: se ogni particella
        // riceve la somma ESATTA delle forze dalle altre (theta=0, nessuna approssimazione), la
        // somma vettoriale delle forze nette su TUTTE le particelle deve essere nulla (nessuna
        // "forza fantasma" che sposterebbe il baricentro del sistema).
        List<Particle> particles = new ArrayList<>();
        particles.add(particleAt(0, 0, 0, 5.0));
        particles.add(particleAt(10, 0, 0, 8.0));
        particles.add(particleAt(0, 10, 0, 3.0));
        particles.add(particleAt(-10, -5, 2, 12.0));
        particles.add(particleAt(4, -4, -4, 6.0));

        BarnesHutTree tree = new BarnesHutTree(particles, 0.0);

        Vector3D netForce = new Vector3D(0, 0, 0);
        for (Particle p : particles) {
            netForce = netForce.add(tree.computeForce(p));
        }

        // Scala di riferimento per la tolleranza: il modulo tipico di una singola forza in gioco.
        double scale = tree.computeForce(particles.get(0)).magnitude();
        assertTrue(netForce.magnitude() < scale * 1e-6,
                "A theta=0 la somma delle forze nette sull'intero sistema deve annullarsi (residuo: "
                        + netForce.magnitude() + ", scala: " + scale + ")");
    }

    @Test
    void largerTheta_neverDivergesWildlyFromDirectSummation_forWellSeparatedClusters() {
        // Non richiediamo l'uguaglianza esatta (l'approssimazione è per definizione approssimata),
        // ma un controllo di sanità: con la theta di default della simulazione, l'errore relativo
        // rispetto alla somma diretta deve restare contenuto per un sistema di cluster ben separati
        // (qui: due gruppi di particelle, ciascuno compatto, a grande distanza reciproca).
        List<Particle> particles = new ArrayList<>();
        // Gruppo A vicino all'origine
        particles.add(particleAt(0, 0, 0, 4.0));
        particles.add(particleAt(2, 1, 0, 6.0));
        particles.add(particleAt(-1, 2, 1, 3.0));
        // Gruppo B lontano, anch'esso compatto
        particles.add(particleAt(500, 0, 0, 9.0));
        particles.add(particleAt(502, -1, 1, 5.0));
        particles.add(particleAt(499, 2, -1, 7.0));

        BarnesHutTree tree = new BarnesHutTree(particles, SimulationConfig.BARNES_HUT_THETA);
        Particle target = particles.get(0);

        Vector3D bhForce = tree.computeForce(target);
        Vector3D directForce = directSumForce(target, particles);

        double relError = bhForce.subtract(directForce).magnitude() / directForce.magnitude();
        assertTrue(relError < 0.05,
                "Con theta di default e cluster ben separati, l'errore relativo di Barnes-Hut deve restare sotto il 5% (osservato: "
                        + (relError * 100) + "%)");
    }
}
