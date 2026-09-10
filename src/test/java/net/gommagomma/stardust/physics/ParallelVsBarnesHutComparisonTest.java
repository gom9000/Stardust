package net.gommagomma.stardust.physics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import net.gommagomma.stardust.PhysicsConstants;
import net.gommagomma.stardust.SimulationParams;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;
import net.gommagomma.stardust.physics.barneshut.BarnesHutTree;

/**
 * Confronta i due percorsi di calcolo delle forze N-corpi che SimulationEngine sceglie in base
 * a N: la somma diretta O(N^2) (quella usata da computeForcesSequential/Parallel) e l'albero di
 * Barnes-Hut (computeForcesBarnesHut). Le due strade DEVONO essere fisicamente equivalenti a meno
 * di un errore di approssimazione contenuto — altrimenti l'engine, passando dall'una all'altra
 * quando N attraversa params.barnesHutThreshold, produrrebbe un salto discontinuo e innaturale
 * nella dinamica proprio nel punto di transizione.
 *
 * A differenza di BarnesHutTreeTest (che isola casi sintetici come un cluster stretto o theta=0
 * esatto), qui si usa una popolazione realistica di N particelle su un disco, con il theta di
 * DEFAULT della simulazione — l'obiettivo è misurare l'errore che ci si aspetta davvero durante
 * una run vera, non il caso limite teorico.
 */
class ParallelVsBarnesHutComparisonTest {

    private static List<Particle> generateDiskLikeCloud(int n, long seed) {
        Random rnd = new Random(seed);
        List<Particle> particles = new ArrayList<>(n);

        double rMin = 0.3 * PhysicsConstants.AU;
        double rMax = 0.7 * PhysicsConstants.AU;

        for (int i = 0; i < n; i++) {
            double r = Math.sqrt(rMin * rMin + rnd.nextDouble() * (rMax * rMax - rMin * rMin));
            double theta = rnd.nextDouble() * 2 * Math.PI;
            double x = r * Math.cos(theta);
            double y = r * Math.sin(theta);
            double z = (rnd.nextDouble() - 0.5) * r * 0.01;

            double mass = 1e19 + rnd.nextDouble() * 1e21; // gamma di masse simile a quella iniziale reale
            particles.add(new Particle(new Vector3D(x, y, z), new Vector3D(0, 0, 0), mass, 0.0, 3000.0));
        }
        return particles;
    }

    /** Riproduce esattamente SimulationEngine.computeForcesParallel/Sequential: somma diretta O(N^2). */
    private static Vector3D[] computeForcesDirect(List<Particle> particles, Physics physics) {
        int n = particles.size();
        Vector3D[] forces = new Vector3D[n];
        for (int i = 0; i < n; i++) {
            Vector3D total = new Vector3D(0, 0, 0);
            Particle p1 = particles.get(i);
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                total = total.add(physics.calculateGravityAndElectrostaticForce(p1, particles.get(j)));
            }
            forces[i] = total;
        }
        return forces;
    }

    private static Vector3D[] computeForcesBarnesHut(List<Particle> particles, SimulationParams params, Physics physics) {
        BarnesHutTree tree = new BarnesHutTree(particles, params, physics);
        int n = particles.size();
        Vector3D[] forces = new Vector3D[n];
        for (int i = 0; i < n; i++) {
            forces[i] = tree.computeForce(particles.get(i));
        }
        return forces;
    }

    @Test
    void barnesHutWithDefaultTheta_agreesClosely_withDirectSummation_onRealisticDiskPopulation() {
        SimulationParams params = new SimulationParams(); // theta di default (0.6), stessa softening ecc.
        Physics physics = new Physics(params);

        int n = 300; // abbastanza per avere una vera distribuzione spaziale, abbastanza poco per l'O(N^2) nel test
        List<Particle> particles = generateDiskLikeCloud(n, 42L);

        Vector3D[] direct = computeForcesDirect(particles, physics);
        Vector3D[] barnesHut = computeForcesBarnesHut(particles, params, physics);

        // Metrica: errore ASSOLUTO scalato sulla forza MEDIA del sistema, non sulla forza propria
        // di ciascuna particella. Motivo (verificato empiricamente): alcune particelle si trovano
        // in punti di parziale cancellazione locale delle forze, dove la forza diretta netta è
        // piccola per puro caso geometrico — dividere per quel valore quasi nullo amplifica
        // artificialmente l'errore relativo (fino al 18% osservato per una singola particella),
        // pur essendo l'errore assoluto del tutto modesto. Scalare sulla forza media del sistema
        // misura invece quanto l'errore conta DAVVERO per la dinamica, non un artefatto statistico.
        double meanDirectMag = 0.0;
        for (Vector3D f : direct) meanDirectMag += f.magnitude();
        meanDirectMag /= n;

        double maxScaledError = 0.0;
        double sumScaledError = 0.0;

        for (int i = 0; i < n; i++) {
            double absError = barnesHut[i].subtract(direct[i]).magnitude();
            double scaledError = absError / meanDirectMag;
            maxScaledError = Math.max(maxScaledError, scaledError);
            sumScaledError += scaledError;
        }
        double meanScaledError = sumScaledError / n;

        assertTrue(meanScaledError < 0.02,
                "L'errore assoluto MEDIO (scalato sulla forza media del sistema) deve restare sotto il 2% "
                        + "su una popolazione realistica (osservato: " + (meanScaledError * 100) + "%)");
        assertTrue(maxScaledError < 0.10,
                "L'errore assoluto MASSIMO (scalato sulla forza media del sistema) deve restare sotto il 10% "
                        + "(osservato: " + (maxScaledError * 100) + "%)");
    }

    @Test
    void barnesHutWithDefaultTheta_neverFlipsForceDirection_onRealisticDiskPopulation() {
        // Controllo di sanità distinto dalla magnitudo: l'approssimazione può sbagliare "quanto"
        // ma non dovrebbe mai sbagliare "verso dove" in modo grossolano (angolo tra le due forze
        // vicino a 180°), altrimenti il moto risultante sarebbe qualitativamente diverso, non solo
        // numericamente meno preciso.
        SimulationParams params = new SimulationParams();
        Physics physics = new Physics(params);

        int n = 300;
        List<Particle> particles = generateDiskLikeCloud(n, 7L);

        Vector3D[] direct = computeForcesDirect(particles, physics);
        Vector3D[] barnesHut = computeForcesBarnesHut(particles, params, physics);

        int flipped = 0;
        for (int i = 0; i < n; i++) {
            double dm = direct[i].magnitude();
            double bm = barnesHut[i].magnitude();
            if (dm <= 0 || bm <= 0) continue;

            double cosAngle = direct[i].dotProduct(barnesHut[i]) / (dm * bm);
            if (cosAngle < 0.9) { // angolo > ~25 gradi tra le due direzioni: sospetto
                flipped++;
            }
        }

        assertTrue(flipped == 0,
                "Nessuna particella dovrebbe avere una direzione della forza sensibilmente diversa (>~25°) "
                        + "tra somma diretta e Barnes-Hut; particelle sospette: " + flipped + "/" + n);
    }
}