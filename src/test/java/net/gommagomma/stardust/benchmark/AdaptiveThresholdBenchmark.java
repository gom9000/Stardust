package net.gommagomma.stardust.benchmark;

import net.gommagomma.stardust.SimulationParams;
import net.gommagomma.stardust.TestParams;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;
import net.gommagomma.stardust.physics.Physics;

/**
 * Confronta, per diversi valori di courantSafetyThreshold (0.25, 0.5, 0.75, 1.0), quanto il
 * timestep adattivo (con pavimento minDtFraction=0.01, la scelta validata in
 * CollisionFidelityDiagnostic) migliora la fedeltà del rilevamento collisioni rispetto a nessun
 * adattamento -- per un incontro ravvicinato controllato a parametro d'impatto variabile.
 *
 * Non è un vero/falso: per ogni configurazione calcola l'errore relativo tra la distanza minima
 * PREVISTA dal CCD (Physics.checkCollision, stessa formula) e quella VERA (ri-integrazione a
 * risoluzione fine dello stesso identico intervallo) -- lo stesso metodo di
 * CollisionFidelityDiagnostic, qui applicato a una soglia variabile invece che a un pavimento
 * variabile.
 *
 * Non è un test JUnit: nessuna asserzione, solo una tabella da leggere.
 */
public class AdaptiveThresholdBenchmark {

    private static final double MASS = 7e23;
    private static final double DENSITY = 3000.0;
    private static final double ORBITAL_OFFSET = 0.5 * 1.496e11;
    private static final int FINE_SUBSTEPS_PER_LARGE_STEP = 2000;
    private static final double MIN_DT_FRACTION = 0.01; // pavimento validato in CollisionFidelityDiagnostic

    public static void main(String[] args) {
        SimulationParams params = TestParams.defaults();
        params.minDtFraction = MIN_DT_FRACTION;
        Physics physics = new Physics(params);

        Particle probe = new Particle(new Vector3D(ORBITAL_OFFSET, 0, 0), new Vector3D(0, 0, 0), MASS, 0.0, DENSITY);
        double combinedCaptureRadius = 2 * physics.getEffectiveCaptureRadius(probe);
        System.out.printf("Raggio di cattura combinato: %.4e m%n", combinedCaptureRadius);
        System.out.printf("Pavimento minDtFraction fissato a %.2f per tutto il confronto%n%n", MIN_DT_FRACTION);

        double[] dtsToTry = {300.0, 600.0, 900.0, 1200.0, 1800.0, 3600.0, 36000.0};
        double[] thresholds = {0.25, 0.5, 0.75, 1.0};
        double vRel = 900.0; // sotto la fuga reciproca, incontro legato e curvo

        // raccolta per la tabella riassuntiva finale: una riga per dt, una colonna per NAIVE + ogni soglia
        double[] summaryNaive = new double[dtsToTry.length];
        double[][] summaryPerThreshold = new double[dtsToTry.length][thresholds.length];

        for (int dtIdx = 0; dtIdx < dtsToTry.length; dtIdx++) {
            double largeDt = dtsToTry[dtIdx];
            System.out.printf("### dt nominale = %.0f s ###%n", largeDt);
            System.out.printf("%-10s | %-12s | %-14s | %-14s | %-14s | %-14s%n",
                    "b/capRad", "err.NAIVE", "err.th=0.25", "err.th=0.50", "err.th=0.75", "err.th=1.00");
            System.out.println("-".repeat(90));

            double totalTime = 4.0 * (2.0 * combinedCaptureRadius / vRel);
            double xSep = vRel * totalTime / 2.0;

            double worstNaive = 0;
            double[] worstPerThreshold = new double[thresholds.length];
            double worstBNaive = 0;
            double[] worstBPerThreshold = new double[thresholds.length];

            for (double bFactor = 0.3; bFactor <= 2.0; bFactor += 0.05) {
                double b = bFactor * combinedCaptureRadius;

                double errNaive = relError(analyzeEncounter(physics, params, xSep, b, vRel, largeDt, totalTime, false, 0.0));
                if (errNaive > worstNaive) { worstNaive = errNaive; worstBNaive = bFactor; }

                double[] errPerThreshold = new double[thresholds.length];
                for (int i = 0; i < thresholds.length; i++) {
                    errPerThreshold[i] = relError(analyzeEncounter(physics, params, xSep, b, vRel, largeDt, totalTime, true, thresholds[i]));
                    if (errPerThreshold[i] > worstPerThreshold[i]) {
                        worstPerThreshold[i] = errPerThreshold[i];
                        worstBPerThreshold[i] = bFactor;
                    }
                }

                System.out.printf("%-10.3f | %-12.4f | %-14.4f | %-14.4f | %-14.4f | %-14.4f%n",
                        bFactor, errNaive, errPerThreshold[0], errPerThreshold[1], errPerThreshold[2], errPerThreshold[3]);
            }

            System.out.printf("%nPeggiore NAIVE:        %.4f (%.1f%%) a b/capRad=%.3f%n",
                    worstNaive, worstNaive * 100, worstBNaive);
            for (int i = 0; i < thresholds.length; i++) {
                System.out.printf("Peggiore soglia=%.2f:   %.4f (%.1f%%) a b/capRad=%.3f%n",
                        thresholds[i], worstPerThreshold[i], worstPerThreshold[i] * 100, worstBPerThreshold[i]);
            }
            System.out.println();

            summaryNaive[dtIdx] = worstNaive;
            summaryPerThreshold[dtIdx] = worstPerThreshold;
        }

        printSummaryTable(dtsToTry, thresholds, summaryNaive, summaryPerThreshold);
    }

    /** Tabella riassuntiva finale: solo i casi peggiori, una riga per dt, pronta da leggere senza
     *  dover scorrere tutto l'output dettagliato sopra. */
    private static void printSummaryTable(double[] dtsToTry, double[] thresholds,
                                            double[] summaryNaive, double[][] summaryPerThreshold) {
        System.out.println("=".repeat(90));
        System.out.println("TABELLA RIASSUNTIVA -- solo i casi peggiori (errore relativo massimo per configurazione)");
        System.out.println("=".repeat(90));

        System.out.printf("%-14s | %-10s", "dt nominale", "NAIVE");
        for (double th : thresholds) {
            System.out.printf(" | soglia=%.2f", th);
        }
        System.out.println();
        System.out.println("-".repeat(90));

        for (int dtIdx = 0; dtIdx < dtsToTry.length; dtIdx++) {
            System.out.printf("%-14.0f | %-10s", dtsToTry[dtIdx], String.format("%.1f%%", summaryNaive[dtIdx] * 100));
            for (int i = 0; i < thresholds.length; i++) {
                System.out.printf(" | %-13s", String.format("%.1f%%", summaryPerThreshold[dtIdx][i] * 100));
            }
            System.out.println();
        }
        System.out.println();
    }

    private static double relError(double[] predictedActual) {
        return Math.abs(predictedActual[0] - predictedActual[1]) / predictedActual[1];
    }

    /** Ritorna {distanza minima PREVISTA dal CCD nello step piu' vicino al vero incrocio,
     *  distanza minima VERA nello stesso identico intervallo temporale}. Se adaptive=true, dt
     *  viene ridotto step per step con la stessa identica formula di SimulationEngine.step(),
     *  usando la soglia data (non params.courantSafetyThreshold, per poterla variare liberamente
     *  senza toccare l'oggetto params condiviso a meta' del ciclo). */
    private static double[] analyzeEncounter(Physics physics, SimulationParams params, double xSep, double b,
                                               double vRel, double nominalDt, double totalTime,
                                               boolean adaptive, double threshold) {
        Particle p1 = new Particle(new Vector3D(ORBITAL_OFFSET - xSep / 2, -b / 2, 0), new Vector3D(vRel / 2, 0, 0), MASS, 0.0, DENSITY);
        Particle p2 = new Particle(new Vector3D(ORBITAL_OFFSET + xSep / 2, b / 2, 0), new Vector3D(-vRel / 2, 0, 0), MASS, 0.0, DENSITY);

        double bestPredicted = Double.MAX_VALUE;
        double bestActual = Double.MAX_VALUE;
        double elapsed = 0.0;
        double savedNominalDt = params.dt;

        while (elapsed < totalTime) {
            double dt = Math.min(nominalDt, totalTime - elapsed);

            if (adaptive) {
                double reach1 = physics.getCaptureReach(p1);
                double reach2 = physics.getCaptureReach(p2);
                double dist = p1.getPosition().subtract(p2.getPosition()).magnitude();
                double courant = 0.0;
                if (dist <= reach1 + reach2) {
                    double sumRadii = p1.getRadius() + p2.getRadius();
                    double relSpeed = p1.getVelocity().subtract(p2.getVelocity()).magnitude();
                    if (sumRadii > 0) courant = (relSpeed * dt) / sumRadii;
                }
                if (courant > threshold) {
                    double scale = threshold / courant;
                    dt = Math.max(dt * scale, nominalDt * params.minDtFraction);
                }
            }
            params.dt = dt;

            Vector3D p1StartPos = p1.getPosition();
            Vector3D p1StartVel = p1.getVelocity();
            Vector3D p2StartPos = p2.getPosition();
            Vector3D p2StartVel = p2.getVelocity();

            stepPair(physics, p1, p2, dt);

            Vector3D r0 = p1StartPos.subtract(p2StartPos);
            Vector3D vRelVec = p1.getVelocity().subtract(p2.getVelocity());
            double a = vRelVec.magnitudeSquared();
            double predictedMinDist;
            if (a == 0.0) {
                predictedMinDist = r0.magnitude();
            } else {
                double t = -r0.dotProduct(vRelVec) / a;
                t = Math.max(0.0, Math.min(dt, t));
                predictedMinDist = r0.add(vRelVec.multiply(t)).magnitude();
            }

            double fineDist = trueMinDistanceOverWindow(physics, p1StartPos, p1StartVel, p2StartPos, p2StartVel, dt);

            if (fineDist < bestActual) {
                bestActual = fineDist;
                bestPredicted = predictedMinDist;
            }

            elapsed += dt;
            params.dt = savedNominalDt;
        }

        return new double[]{bestPredicted, bestActual};
    }

    private static double trueMinDistanceOverWindow(Physics physics, Vector3D p1Pos, Vector3D p1Vel,
                                                       Vector3D p2Pos, Vector3D p2Vel, double dt) {
        Particle p1 = new Particle(p1Pos, p1Vel, MASS, 0.0, DENSITY);
        Particle p2 = new Particle(p2Pos, p2Vel, MASS, 0.0, DENSITY);

        double fineDt = dt / FINE_SUBSTEPS_PER_LARGE_STEP;
        double minDist = p1.getPosition().subtract(p2.getPosition()).magnitude();

        for (int i = 0; i < FINE_SUBSTEPS_PER_LARGE_STEP; i++) {
            stepPair(physics, p1, p2, fineDt);
            double d = p1.getPosition().subtract(p2.getPosition()).magnitude();
            if (d < minDist) minDist = d;
        }
        return minDist;
    }

    private static void stepPair(Physics physics, Particle p1, Particle p2, double dt) {
        p1.resetForce();
        p2.resetForce();
        Vector3D f = physics.calculateGravityAndElectrostaticForce(p1, p2);
        p1.addForce(f);
        p2.addForce(f.multiply(-1));
        p1.update(dt);
        p2.update(dt);
    }
}