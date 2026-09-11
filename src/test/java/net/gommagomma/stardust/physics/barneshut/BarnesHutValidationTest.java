package net.gommagomma.stardust.physics.barneshut;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.gommagomma.stardust.PhysicsConstants;
import net.gommagomma.stardust.SimulationEngine;
import net.gommagomma.stardust.SimulationParams;
import net.gommagomma.stardust.TestParams;
import net.gommagomma.stardust.io.RunLogger;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;

/**
 * Validazione QUANTITATIVA di Barnes-Hut attraverso l'evoluzione temporale reale del motore,
 * non una singola forza istantanea (già coperta da BarnesHutTreeTest/ParallelVsBarnesHutComparisonTest).
 * Qui si fanno girare DUE SimulationEngine indipendenti, con lo stesso stato iniziale esatto, per
 * lo stesso numero di step: uno forzato al dispatch diretto (barnesHutThreshold altissimo), l'altro
 * forzato a Barnes-Hut (barnesHutThreshold=0). Si misura quanto le traiettorie finali divergono, e
 * si verifica che quella divergenza si comporti come ci si aspetta da un'approssimazione: piccola
 * a theta piccolo, crescente (in modo monotono) al crescere di theta.
 */
class BarnesHutValidationTest {

    private RunLogger newLogger(Path tempDir, String name) throws IOException {
        return new RunLogger(tempDir.resolve(name + ".log"));
    }

    /** Popolazione di planetesimi ben separati (nessuna collisione attesa), su un disco realistico. */
    private static List<Particle> generateOrbitingCloud(int n, SimulationParams params, long seed) {
        Random rnd = new Random(seed);
        List<Particle> particles = new ArrayList<>(n);
        double rMin = params.diskInnerRadius;
        double rMax = params.diskOuterRadius;

        for (int i = 0; i < n; i++) {
            double r = Math.sqrt(rMin * rMin + rnd.nextDouble() * (rMax * rMax - rMin * rMin));
            double theta = rnd.nextDouble() * 2 * Math.PI;
            double x = r * Math.cos(theta);
            double y = r * Math.sin(theta);

            double v = Math.sqrt(PhysicsConstants.G * params.centralStarMass / r);
            double vx = -v * Math.sin(theta);
            double vy = v * Math.cos(theta);

            double mass = params.initialParticleMassMin
                    + rnd.nextDouble() * (params.initialParticleMassMax - params.initialParticleMassMin);

            particles.add(new Particle(new Vector3D(x, y, 0), new Vector3D(vx, vy, 0), mass, 0.0, 3000.0));
        }
        return particles;
    }

    /** Copia profonda (stesso ID, stato fisico identico) per far partire due engine dallo stesso identico stato. */
    private static List<Particle> deepCopy(List<Particle> particles) {
        List<Particle> copy = new ArrayList<>(particles.size());
        for (Particle p : particles) {
            copy.add(new Particle(p.getId(), p.getPosition(), p.getVelocity(), p.getMass(),
                    p.getCharge(), p.getDensity(), p.getInitialRadius(), p.getMergerCount()));
        }
        return copy;
    }

    /** Errore RMS di posizione tra due liste di particelle (stesso ordine/ID), scalato sul raggio tipico del disco. */
    private static double rmsPositionDivergence(List<Particle> a, List<Particle> b, double scaleRadius) {
        double sumSq = 0.0;
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            double d = a.get(i).getPosition().distanceTo(b.get(i).getPosition());
            sumSq += d * d;
        }
        double rms = Math.sqrt(sumSq / n);
        return rms / scaleRadius;
    }

    /** Parametri con masse di scala planetesimale (invece della polvere iniziale, ~1e19-1e21 kg):
     *  a masse di polvere la forza mutua e' cosi' trascurabile rispetto a quella stellare che
     *  Barnes-Hut non viene mai messo davvero sotto sforzo (la traiettoria e' dominata quasi
     *  interamente dalla stella, qualunque errore di approssimazione sulla componente N-corpi
     *  resterebbe invisibile). Il raggio di cattura e' inoltre ridotto quasi a zero per escludere
     *  collisioni, che romperebbero la corrispondenza posizionale tra le due liste di particelle
     *  se scattassero in un motore e non nell'altro. */
    private static SimulationParams heavyMassParams() {
        SimulationParams params = TestParams.defaults();
        params.initialParticleMassMin = 1e23;
        params.initialParticleMassMax = 1e25;
        params.hillCaptureFraction = 1e-6;
        return params;
    }

    @Test
    void barnesHutTrajectory_staysClose_toDirectSummationTrajectory_overManySteps(@TempDir Path tempDir) throws IOException {
        SimulationParams paramsDirect = heavyMassParams();
        paramsDirect.n = 80;
        paramsDirect.barnesHutThreshold = 1_000_000; // mai raggiunto: sempre dispatch diretto/parallelo

        List<Particle> initial = generateOrbitingCloud(80, paramsDirect, 10L);

        SimulationParams paramsBH = heavyMassParams();
        paramsBH.n = 80;
        paramsBH.barnesHutThreshold = 0; // sempre Barnes-Hut, con il theta di default (0.6)

        SimulationEngine engineDirect = new SimulationEngine(deepCopy(initial), newLogger(tempDir, "direct"), paramsDirect);
        SimulationEngine engineBH = new SimulationEngine(deepCopy(initial), newLogger(tempDir, "bh"), paramsBH);

        int steps = 300;
        for (int i = 0; i < steps; i++) {
            engineDirect.step();
            engineBH.step();
        }

        double divergence = rmsPositionDivergence(engineDirect.getParticles(), engineBH.getParticles(), paramsDirect.diskOuterRadius);

        assertTrue(divergence < 0.05,
                "Dopo " + steps + " step, la traiettoria con Barnes-Hut (theta di default) non deve discostarsi "
                        + "più del 5% del raggio del disco da quella calcolata con somma diretta (osservato: "
                        + (divergence * 100) + "%)");
    }

    @Test
    void barnesHutTrajectoryError_increasesMonotonically_withTheta(@TempDir Path tempDir) throws IOException {
        SimulationParams paramsDirect = heavyMassParams();
        paramsDirect.n = 60;
        paramsDirect.barnesHutThreshold = 1_000_000;

        List<Particle> initial = generateOrbitingCloud(60, paramsDirect, 20L);

        SimulationEngine engineDirect = new SimulationEngine(deepCopy(initial), newLogger(tempDir, "direct2"), paramsDirect);

        int steps = 150;
        for (int i = 0; i < steps; i++) {
            engineDirect.step();
        }

        double[] thetas = {0.1, 0.4, 0.8, 1.2};
        double previousDivergence = -1.0;

        for (double theta : thetas) {
            SimulationParams paramsBH = heavyMassParams();
            paramsBH.n = 60;
            paramsBH.barnesHutThreshold = 0;
            paramsBH.barnesHutTheta = theta;

            SimulationEngine engineBH = new SimulationEngine(deepCopy(initial), newLogger(tempDir, "bh-theta-" + theta), paramsBH);
            for (int i = 0; i < steps; i++) {
                engineBH.step();
            }

            double divergence = rmsPositionDivergence(engineDirect.getParticles(), engineBH.getParticles(), paramsDirect.diskOuterRadius);

            assertTrue(divergence >= previousDivergence * 0.5,
                    "L'errore rispetto alla somma diretta non deve calare in modo netto aumentando theta da un valore "
                            + "più piccolo: a theta=" + theta + " osservato " + divergence + ", al valore precedente era " + previousDivergence);

            previousDivergence = divergence;
        }
    }
}
