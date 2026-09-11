package net.gommagomma.stardust.benchmark;

import java.util.List;

import net.gommagomma.stardust.PhysicsConstants;
import net.gommagomma.stardust.SimulationEngine;
import net.gommagomma.stardust.SimulationParams;
import net.gommagomma.stardust.TestParams;
import net.gommagomma.stardust.io.RunLogger;
import net.gommagomma.stardust.model.Particle;

/**
 * Matrice esplorativa dt x theta x DURATA: per ogni combinazione, fa girare SimulationEngine.step()
 * VERO (dispatch Barnes-Hut forzato) fino a coprire un orizzonte di tempo SIMULATO fisso.
 * Drag e collisioni disattivati per isolare la sola dinamica gravitazionale conservativa.
 *
 * ATTENZIONE AL COSTO: un orizzonte più lungo richiede PROPORZIONALMENTE più step a parità di dt
 * (raddoppiare la durata raddoppia gli step, non è un parametro "gratis"). Con i valori di default
 * qui sotto (N=100, dt fino a 1200s), un singolo orizzonte di 100 anni a dt=300s richiede circa
 * 10,5 milioni di step -- alcune decine di minuti per QUELLA sola combinazione. Se vuoi includere
 * DURATIONS_YEARS=100 nella griglia, riduci prima DTS/THETAS a pochi valori mirati, invece di
 * lanciare l'intera griglia incrociata: la stima di step viene comunque stampata PRIMA di partire,
 * cosi' puoi interrompere in tempo se il conto non ti convince.
 *
 * Non è un test JUnit: nessuna asserzione, solo tabelle da leggere.
 */
public class DtThetaDurationEnergyMatrix {

    private static final double[] DTS = {150.0, 300.0, 600.0, 1200.0, 1800.0, 3600.0};
    private static final double[] THETAS = {0.1, 0.6, 1.2};
    private static final double[] DURATIONS_YEARS = {1.0, 5.0, 10.0, 100.0};

    private static final int N = 100;
    private static final int ENERGY_SAMPLES_PER_RUN = 60; // indipendente dal numero di step totali
    private static final long SEED = 7L;

    private static final double SECONDS_PER_YEAR = 86400.0 * 365.25;

    public static void main(String[] args) throws Exception {
        for (double years : DURATIONS_YEARS) {
            double totalSimulatedSeconds = years * SECONDS_PER_YEAR;

            System.out.println();
            System.out.printf("=== Orizzonte: %.1f anni simulati ===%n", years);
            System.out.printf("%-6s %-6s | %10s | %11s %11s | %10s%n",
                    "dt", "theta", "step", "driftMax%", "driftFinale%", "t_wall(s)");
            System.out.println("-".repeat(70));

            for (double dt : DTS) {
                long steps = Math.round(totalSimulatedSeconds / dt);

                // Stima di costo stampata PRIMA di partire, non dopo: se il numero di step e'
                // enorme lo vedi subito e puoi interrompere (Ctrl+C) senza aspettare la fine.
                System.out.printf("  [%.0fs, %.1f anni -> %,d step stimati]%n", dt, years, steps);

                List<Particle> template = BenchmarkFixtures.generateHeavyCloud(N, SEED);

                for (double theta : THETAS) {
                    SimulationParams params = TestParams.defaults();
                    params.dt = dt;
                    params.barnesHutTheta = theta;
                    params.barnesHutThreshold = 0;
                    params.gasDensityBase = 0.0;
                    params.hillCaptureFraction = 1e-6;

                    List<Particle> particles = BenchmarkFixtures.deepCopy(template);
                    SimulationEngine engine = new SimulationEngine(particles,
                            new RunLogger(java.nio.file.Files.createTempFile("dt-theta-dur-", ".log"), false), params);

                    double energyBefore = totalSystemEnergy(engine.getParticles(), params.centralStarMass);
                    double maxDrift = 0.0;

                    long sampleEvery = Math.max(1, steps / ENERGY_SAMPLES_PER_RUN);

                    long wallStart = System.nanoTime();
                    for (long i = 0; i < steps; i++) {
                        engine.step();
                        if (i % sampleEvery == 0) {
                            double e = totalSystemEnergy(engine.getParticles(), params.centralStarMass);
                            maxDrift = Math.max(maxDrift, Math.abs((e - energyBefore) / energyBefore));
                        }
                    }
                    long wallMs = (System.nanoTime() - wallStart) / 1_000_000;

                    double energyAfter = totalSystemEnergy(engine.getParticles(), params.centralStarMass);
                    double finalDrift = Math.abs((energyAfter - energyBefore) / energyBefore);

                    System.out.printf("%-6.0f %-6.1f | %10d | %11.4f %11.4f | %10.2f%n",
                            dt, theta, steps, maxDrift * 100, finalDrift * 100, wallMs / 1000.0);
                }
            }
        }
    }

    private static double mutualPotential(Particle p1, Particle p2) {
        double dist = p1.getPosition().distanceTo(p2.getPosition());
        if (dist <= 0) return 0.0;
        return -(PhysicsConstants.G * p1.getMass() * p2.getMass()) / dist;
    }

    private static double kinetic(Particle p) {
        double v = p.getVelocity().magnitude();
        return 0.5 * p.getMass() * v * v;
    }

    private static double starPotential(Particle p, double centralStarMass) {
        double r = p.getPosition().magnitude();
        if (r <= 0) return 0.0;
        return -(PhysicsConstants.G * centralStarMass * p.getMass()) / r;
    }

    private static double totalSystemEnergy(List<Particle> particles, double centralStarMass) {
        double energy = 0.0;
        for (Particle p : particles) {
            energy += kinetic(p) + starPotential(p, centralStarMass);
        }
        for (int i = 0; i < particles.size(); i++) {
            for (int j = i + 1; j < particles.size(); j++) {
                energy += mutualPotential(particles.get(i), particles.get(j));
            }
        }
        return energy;
    }
}