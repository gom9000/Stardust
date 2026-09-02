package net.gommagomma.stardust.core;

import java.util.ArrayList;
import java.util.List;

public class Physics
{
    //
    // FORZE DI CAMPO E FLUIDO
    //

    /**
     * Forza gravitazionale esercitata da p2 su p1 (sempre attrattiva).
     */
    public static Vector3D calculateGravity(Particle p1, Particle p2) {
        Vector3D diff = p2.getPosition().subtract(p1.getPosition());
        double distanceSquared = diff.magnitudeSquared();

        if (distanceSquared == 0) return new Vector3D(0, 0, 0);

        // r^2 + epsilon^2
        double epsSq = SimulationConfig.SOFTENING * SimulationConfig.SOFTENING;
        double effectiveDistSq = distanceSquared + epsSq;
        double effectiveDist = Math.sqrt(effectiveDistSq);

        // F / |r| = (G * m1 * m2) / (r^2 + epsilon^2)^(3/2)
        double forceFactor = (SimulationConfig.G * p1.getMass() * p2.getMass()) / (effectiveDistSq * effectiveDist);

        return diff.multiply(forceFactor);
    }

    /**
     * Forza attrattiva verso la stella centrale posizionata nell'origine (0,0,0).
     */
    public static Vector3D calculateCentralStarGravity(Particle p) {
        Vector3D pos = p.getPosition();
        double distanceSquared = pos.magnitudeSquared();

        if (distanceSquared == 0) return new Vector3D(0, 0, 0);

        double epsSq = SimulationConfig.SOFTENING * SimulationConfig.SOFTENING;
        double effectiveDistSq = distanceSquared + epsSq;
        double effectiveDist = Math.sqrt(effectiveDistSq);

        // Force = (G * M_star * m) / (r_eff^3)
        double forceFactor = (SimulationConfig.G * SimulationConfig.STAR_MASS * p.getMass()) / (effectiveDistSq * effectiveDist);

        // Direzione verso l'origine (-pos)
        return pos.multiply(-forceFactor);
    }

    /**
     * Forza elettrostatica esercitata da p2 su p1 (repulsiva se cariche concordi).
     */
    public static Vector3D calculateCoulomb(Particle p1, Particle p2) {
        double q1 = p1.getCharge();
        double q2 = p2.getCharge();

        if (q1 == 0.0 || q2 == 0.0) return new Vector3D(0, 0, 0);

        Vector3D diff = p2.getPosition().subtract(p1.getPosition());
        double distanceSquared = diff.magnitudeSquared();

        if (distanceSquared == 0) return new Vector3D(0, 0, 0);

        double epsSq = SimulationConfig.SOFTENING * SimulationConfig.SOFTENING;
        double effectiveDistSq = distanceSquared + epsSq;
        double effectiveDist = Math.sqrt(effectiveDistSq);

        double forceFactor = (SimulationConfig.K_COULOMB * q1 * q2) / (effectiveDistSq * effectiveDist);

        return diff.multiply(-forceFactor);
    }

    /**
     * Calcola l'effetto di drag del gas considerando il profilo di densità 3D (r, z),
     * la velocità sotto-kepleriana data dal gradiente di pressione radiale 
     * e la transizione automatica tra il Regime di Epstein (polveri) e Stokes (corpi estesi).
     */
    public static Vector3D calculateDrag(Particle p) {
        Vector3D pos = p.getPosition();
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();

        double rXY = Math.hypot(x, y);
        double r3D = pos.magnitude();

        if (rXY == 0.0 || r3D == 0.0) return new Vector3D(0, 0, 0);

        // 1. PARAMETRI TERMODINAMICI DEL DISCO DI GAS
        double tempRef = 280.0; // Kelvin a 1 AU
        double temperature = tempRef * Math.pow(r3D / SimulationConfig.AU, -0.5);

        double kB = 1.380649e-23;
        double mH2 = 3.34e-27; // Massa molecola d'idrogeno (kg)
        double soundSpeed = Math.sqrt((kB * temperature) / mH2);

        double omegaK = Math.sqrt((SimulationConfig.G * SimulationConfig.STAR_MASS) / (r3D * r3D * r3D));
        double scaleHeight = soundSpeed / omegaK;

        // 2. PROFILO DI DENSITÀ DEL GAS 3D
        double midplaneGasDensity = SimulationConfig.GAS_DENSITY_BASE * 
                Math.pow(r3D / SimulationConfig.AU, SimulationConfig.GAS_PROFILE_EXPONENT);
        
        double localGasDensity = midplaneGasDensity * Math.exp(-(z * z) / (2.0 * scaleHeight * scaleHeight));

        if (localGasDensity < 1e-25) return new Vector3D(0, 0, 0);

        // 3. VELOCITÀ DEL GAS SOTTO-KEPLERIANA (PRESSIONE RADIALE)
        double hOverR = scaleHeight / r3D;
        double eta = 0.5 * (hOverR * hOverR) * Math.abs(SimulationConfig.GAS_PROFILE_EXPONENT);
        
        double vKeplerian = omegaK * r3D;
        double vGasMag = vKeplerian * Math.sqrt(Math.max(0.0, 1.0 - eta));

        Vector3D vGas = new Vector3D((-y / rXY) * vGasMag, (x / rXY) * vGasMag, 0.0);
        Vector3D vRel = p.getVelocity().subtract(vGas);
        double speedRel = vRel.magnitude();

        if (speedRel == 0.0) return new Vector3D(0, 0, 0);

        // 4. REGIMI DI DRAG (EPSTEIN vs STOKES)
        double sigmaH2 = 2.0e-19;
        double meanFreePath = mH2 / (Math.sqrt(2.0) * sigmaH2 * localGasDensity);

        double R = p.getRadius();
        double forceFactor;

        if (R <= (9.0 / 4.0) * meanFreePath) {
            // Regime di Epstein (Polveri)
            double vThermal = Math.sqrt(8.0 / Math.PI) * soundSpeed;
            forceFactor = -(4.0 / 3.0) * Math.PI * localGasDensity * (R * R) * vThermal;
        } else {
            // Regime di Stokes/Quadratico (Corpi grandi)
            double area = Math.PI * R * R;
            double Cd = 0.44;
            forceFactor = -0.5 * Cd * localGasDensity * area * speedRel;
        }

        return vRel.multiply(forceFactor);
    }

    public static double calculateParticleEnergy(Particle p, List<Particle> allParticles)
    {
        if (!p.isAlive()) return 0.0;

        // Energia Cinetica
        double vMag = p.getVelocity().magnitude();
        double kinetic = 0.5 * p.getMass() * vMag * vMag;

        // Energia Potenziale con la Stella Centrale
        double rStar = p.getPosition().magnitude();
        double potentialStar = (rStar > 0) ? -(SimulationConfig.G * SimulationConfig.STAR_MASS * p.getMass()) / rStar : 0.0;

        // Energia Potenziale Mutua con le altre (divisa per 2 per evitare il doppio conteggio delle coppie)
        double potentialMutual = 0.0;
        for (Particle p2 : allParticles) {
            if (p2 == p || !p2.isAlive()) continue;
            double dist = p.getPosition().distanceTo(p2.getPosition());
            if (dist > 0) {
                potentialMutual -= (SimulationConfig.G * p.getMass() * p2.getMass()) / dist;
            }
        }
        potentialMutual *= 0.5;

        return kinetic + potentialStar + potentialMutual;
    }

    //
    // RAGGI E GEOMETRIA DI CATTURA
    //

    public static double getHillRadius(Particle p) {
        double r = p.getPosition().magnitude();
        return r * Math.cbrt(p.getMass() / (3.0 * SimulationConfig.STAR_MASS));
    }

    public static double getEffectiveCaptureRadius(Particle p) {
        double hillCapture = getHillRadius(p) * SimulationConfig.HILL_CAPTURE_FRACTION;
        return Math.max(p.getRadius(), hillCapture);
    }

    public static double getCaptureReach(Particle p) {
        double hillReach = getHillRadius(p) * SimulationConfig.HILL_AMPLIFICATION;
        return Math.max(p.getRadius(), hillReach);
    }

    //
    // COLLISIONI E RISOLUZIONE
    //

    /**
     * Determina se due particelle sono abbastanza vicine da collidere nel timestep dt.
     */
    public static boolean checkCollision(Particle p1, Particle p2) {
        double distance = p1.getPosition().distanceTo(p2.getPosition());
        double combinedCaptureRadius = getEffectiveCaptureRadius(p1) + getEffectiveCaptureRadius(p2);
        
        double relSpeed = p1.getVelocity().subtract(p2.getVelocity()).magnitude();
        double sweptBuffer = relSpeed * SimulationConfig.DT;

        return distance <= (combinedCaptureRadius + sweptBuffer);
    }

    /**
     * Valuta l'esito della collisione tra due particelle selezionando tra FUSIONE, RIMBALZO o FRAMMENTAZIONE.
     */
    public static CollisionResult evaluateCollision(Particle p1, Particle p2)
    {
        Vector3D relVel = p1.getVelocity().subtract(p2.getVelocity());
        double relSpeed = relVel.magnitude();
        double totalMass = p1.getMass() + p2.getMass();

        double captureRadiusSum = getEffectiveCaptureRadius(p1) + getEffectiveCaptureRadius(p2);
        double distance = Math.max(p1.getPosition().distanceTo(p2.getPosition()), captureRadiusSum);

        // Velocità di fuga reciproca dal punto di impatto
        double escapeVelocity = Math.sqrt((2.0 * SimulationConfig.G * totalMass) / distance);

        // Soglia di FUSIONE / CATTURA
        double captureThreshold = escapeVelocity * SimulationConfig.GRAVITATIONAL_CAPTURE_MULTIPLIER;
        double effectiveCaptureThreshold = Math.max(SimulationConfig.DUST_COHESION_THRESHOLD, captureThreshold);

        // Soglia di FRAMMENTAZION
        double fragMultiplier = Math.max(1.0, SimulationConfig.FRAGMENTATION_MULTIPLIER);
        double fragmentationThreshold = effectiveCaptureThreshold * fragMultiplier;

        if (relSpeed <= effectiveCaptureThreshold) {
            return CollisionResult.MERGE;
        }
        if (relSpeed > fragmentationThreshold) {
            return CollisionResult.FRAGMENT;
        }
        return CollisionResult.BOUNCE;
    }

    /**
     * Risolve l'urto anelastico fondendo la particella 'loser' dentro la particella 'winner'.
     */
    public static void mergeParticles(Particle winner, Particle loser) {
        double totalMass = winner.getMass() + loser.getMass();

        Vector3D newPos = winner.getPosition().multiply(winner.getMass())
                .add(loser.getPosition().multiply(loser.getMass()))
                .divide(totalMass);

        Vector3D newVel = winner.getVelocity().multiply(winner.getMass())
                .add(loser.getVelocity().multiply(loser.getMass()))
                .divide(totalMass);

        double blendedDensity = (winner.getDensity() * winner.getMass() + loser.getDensity() * loser.getMass()) / totalMass;
        double newDensity = (blendedDensity < 3000.0) ? Math.min(3000.0, blendedDensity * 1.02) : 3000.0;

        winner.setPosition(newPos);
        winner.setVelocity(newVel);
        winner.setCharge(winner.getCharge() + loser.getCharge());
        winner.setMass(totalMass);
        winner.setDensity(newDensity);
        winner.incrementMergerCount(loser.getMergerCount() + 1);
        winner.updateRadius();

        loser.setAlive(false);
    }

    /**
     * Risolve l'urto cinematico (rimbalzo) tra due particelle conservando la quantità di moto.
     * Separa fisicamente i corpi sovrapposti per evitare il fenomeno di interpenetrazione continua.
     */
    public static void resolveBounce(Particle p1, Particle p2, double restitution) {
        Vector3D deltaPos = p1.getPosition().subtract(p2.getPosition());
        double dist = deltaPos.magnitude();
        
        if (dist == 0) return;

        Vector3D normal = deltaPos.divide(dist);
        Vector3D relVel = p1.getVelocity().subtract(p2.getVelocity());
        double velAlongNormal = relVel.dotProduct(normal);

        // Se le particelle si stanno già allontanando, nessuna azione sugli impulsi
        if (velAlongNormal < 0) {
            double totalMass = p1.getMass() + p2.getMass();
            double inverseMassSum = (1.0 / p1.getMass()) + (1.0 / p2.getMass());
            
            // Calcolo dell'impulso
            double impulseMagnitude = -(1.0 + restitution) * velAlongNormal / inverseMassSum;
            Vector3D impulseVector = normal.multiply(impulseMagnitude);

            p1.setVelocity(p1.getVelocity().add(impulseVector.divide(p1.getMass())));
            p2.setVelocity(p2.getVelocity().subtract(impulseVector.divide(p2.getMass())));
        }

        // CORREZIONE POSIZIONALE (Separa i due corpi se penetrati)
        double targetDist = getEffectiveCaptureRadius(p1) + getEffectiveCaptureRadius(p2);
        double overlap = targetDist - dist;

        if (overlap > 0.0) {
            double totalMass = p1.getMass() + p2.getMass();
            // Lo spostamento è proporzionale all'inverso della massa (il corpo più leggero si sposta di più)
            Vector3D p1Corr = normal.multiply(overlap * (p2.getMass() / totalMass));
            Vector3D p2Corr = normal.multiply(-overlap * (p1.getMass() / totalMass));

            p1.setPosition(p1.getPosition().add(p1Corr));
            p2.setPosition(p2.getPosition().add(p2Corr));
        }
    }


    /**
     * Esegue la frammentazione catastrofica di due particelle che collidono.
     * Segna p1 e p2 come morte e genera una lista di frammenti minori derivati dall'urto.
     *
     * @param p1 Prima particella
     * @param p2 Seconda particella
     * @return Lista dei frammenti generati
     */
    public static List<Particle> fragmentParticles(Particle p1, Particle p2) {
        List<Particle> fragments = new ArrayList<>();
        double totalMass = p1.getMass() + p2.getMass();
        int numFragments = 2 + (int)(Math.random() * 4);

        // 1. Centro di massa e velocità del sistema (Conservazione Q.d.M.)
        Vector3D comPos = p1.getPosition().multiply(p1.getMass())
                .add(p2.getPosition().multiply(p2.getMass()))
                .divide(totalMass);

        Vector3D comVel = p1.getVelocity().multiply(p1.getMass())
                .add(p2.getVelocity().multiply(p2.getMass()))
                .divide(totalMass);

        // 2. Energia cinetica d'impatto disponibile nel sistema del centro di massa
        Vector3D vRel = p1.getVelocity().subtract(p2.getVelocity());
        double reducedMass = (p1.getMass() * p2.getMass()) / totalMass;
        double kineticEnergyCom = 0.5 * reducedMass * vRel.magnitudeSquared();

        // L'energia di frammentazione dissipa energia: solo una frazione (es. 10%) diventa energia di espulsione cinetica
        double ejectionEnergy = kineticEnergyCom * 0.10;
        double avgEjectionSpeed = Math.sqrt((2.0 * ejectionEnergy) / totalMass);

        double avgDensity = (p1.getDensity() * p1.getMass() + p2.getDensity() * p2.getMass()) / totalMass;

        // 3. Generazione provvisoria delle masse e delle velocità di espulsione relative (u_i)
        double remainingMass = totalMass;
        List<Double> masses = new ArrayList<>();
        List<Vector3D> relativeVelocities = new ArrayList<>();
        List<Vector3D> positions = new ArrayList<>();

        for (int i = 0; i < numFragments; i++) {
            double massFraction;
            if (i == numFragments - 1) {
                massFraction = remainingMass;
            } else {
                double factor = Math.pow((numFragments - i) / (double) numFragments, 2.0);
                massFraction = Math.min(remainingMass * 0.7, totalMass * 0.5 * (factor / numFragments));
            }

            if (massFraction <= 1e-50) continue;
            remainingMass -= massFraction;
            masses.add(massFraction);

            // Direzione isotropa casuale
            double theta = Math.random() * 2.0 * Math.PI;
            double phi = Math.acos(2.0 * Math.random() - 1.0);
            Vector3D ejectionDir = new Vector3D(Math.sin(phi) * Math.cos(theta), Math.sin(phi) * Math.sin(theta), Math.cos(phi));

            double speedBoost = avgEjectionSpeed * (0.5 + Math.random() * 0.5);
            relativeVelocities.add(ejectionDir.multiply(speedBoost));

            double estimatedRadius = Math.cbrt((3.0 * massFraction) / (4.0 * Math.PI * avgDensity));
            positions.add(comPos.add(ejectionDir.multiply(estimatedRadius * 4.0)));
        }

        // 4. CORREZIONE DELLA QUANTITÀ DI MOTO: Assicura rigorosamente che sum(m_i * u_i) = 0
        Vector3D weightedMomentumSum = new Vector3D(0, 0, 0);
        double actualTotalMass = 0;
        for (int i = 0; i < masses.size(); i++) {
            weightedMomentumSum = weightedMomentumSum.add(relativeVelocities.get(i).multiply(masses.get(i)));
            actualTotalMass += masses.get(i);
        }
        Vector3D momentumCorrection = weightedMomentumSum.divide(actualTotalMass);

        // 5. Istanziazione finale dei frammenti bilanciati
        for (int i = 0; i < masses.size(); i++) {
            double m = masses.get(i);
            // Sottrae la deriva di momento per rispettare la fisica del centro di massa
            Vector3D correctedRelVel = relativeVelocities.get(i).subtract(momentumCorrection);
            Vector3D finalVel = comVel.add(correctedRelVel);

            double fragmentCharge = (p1.getCharge() + p2.getCharge()) * (m / totalMass);

            Particle frag = new Particle(positions.get(i), finalVel, m, fragmentCharge, avgDensity);
            fragments.add(frag);
        }

        p1.setAlive(false);
        p2.setAlive(false);

        return fragments;
    }
}