package net.gommagomma.stardust.physics;

import java.util.ArrayList;
import java.util.List;

import net.gommagomma.stardust.PhysicsConstants;
import net.gommagomma.stardust.SimulationParams;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;
import net.gommagomma.stardust.physics.collision.CollisionResult;
import net.gommagomma.stardust.physics.gravity.GravityCalculator;

public class Physics
{
	private final SimulationParams params;
	private final GravityCalculator gravitycalculator;

	public Physics(SimulationParams params) {
        this.params = params;
        this.gravitycalculator = new GravityCalculator(params);
    }


    //
    // FORZE DI CAMPO E FLUIDO
    //

	/**
	 * Forza netta N-body esercitata da p2 su p1: somma della componente gravitazionale e,
	 * se params.enableElectrostaticForce è attivo, della componente elettrostatica (Coulomb).
	 */
	public Vector3D calculateGravityAndElectrostaticForce(Particle p1, Particle p2) {
	    Vector3D f = calculateGravity(p1, p2);
	    if (params.enableElectrostaticForce) {
	        f = f.add(calculateCoulomb(p1, p2));
	    }
	    return f;
	}

	/**
	 * Forza gravitazionale newtoniana esercitata da p2 su p1. 
	 */
	public Vector3D calculateGravity(Particle p1, Particle p2)
	{
        return gravitycalculator.calculateGravity(p1, p2, params.activeGravityModel);
    }

    /**
     * Forza attrattiva verso la stella.
     */
    public Vector3D calculateCentralStarGravity(Particle p) {
    	return gravitycalculator.calculateGravity(p, params.centralStar);
    }

    /**
     * Energia potenziale gravitazionale tra la particella e la stella centrale.
     * Termine a un corpo (non va diviso per 2 come il potenziale mutuo tra particelle).
     */
    public double calculateCentralStarPotentialEnergy(Particle p) {
        double r = p.getPosition().magnitude();
        if (r <= 0.0) return 0.0;
        return -(PhysicsConstants.G * params.centralStarMass * p.getMass()) / r;
    }
    
    /**
     * Forza elettrostatica esercitata da p2 su p1 (repulsiva se cariche concordi).
     */
    public Vector3D calculateCoulomb(Particle p1, Particle p2) {
        double q1 = p1.getCharge();
        double q2 = p2.getCharge();

        if (q1 == 0.0 || q2 == 0.0) return new Vector3D(0, 0, 0);

        Vector3D diff = p2.getPosition().subtract(p1.getPosition());
        double distanceSquared = diff.magnitudeSquared();

        if (distanceSquared == 0) return new Vector3D(0, 0, 0);

        double epsSq = params.softening * params.softening;
        double effectiveDistSq = distanceSquared + epsSq;
        double effectiveDist = Math.sqrt(effectiveDistSq);

        double forceFactor = (PhysicsConstants.K_COULOMB * q1 * q2) / (effectiveDistSq * effectiveDist);

        return diff.multiply(-forceFactor);
    }

    /**
     * Calcola l'effetto di drag del gas considerando il profilo di densità 3D (r, z),
     * la velocità sotto-kepleriana data dal gradiente di pressione radiale 
     * e la transizione automatica tra il Regime di Epstein (polveri) e Stokes (corpi estesi).
     */
    public Vector3D calculateDrag(Particle p) {
        Vector3D pos = p.getPosition();
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();

        double rXY = Math.hypot(x, y);
        double r3D = pos.magnitude();

        if (rXY == 0.0 || r3D == 0.0) return new Vector3D(0, 0, 0);

        // 1. PARAMETRI TERMODINAMICI DEL DISCO DI GAS
        double tempRef = 280.0; // Kelvin a 1 AU
        double temperature = tempRef * Math.pow(r3D / PhysicsConstants.AU, params.diskTemperatureExponent);

        double kB = 1.380649e-23;
        double mH2 = 3.34e-27; // Massa molecola d'idrogeno (kg)
        double soundSpeed = Math.sqrt((kB * temperature) / mH2);

        double omegaK = Math.sqrt((PhysicsConstants.G * params.centralStarMass) / (r3D * r3D * r3D));
        double scaleHeight = soundSpeed / omegaK;

        // 2. PROFILO DI DENSITÀ DEL GAS 3D
        // L'esponente di densita' NON e' un parametro libero: e' derivato dalla stessa temperatura
        // usata sopra, per costruzione, cosicche' i due profili (temperatura e densita') non possano
        // mai andare fuori sincrono come accadeva prima (temperatura ~r^-0.5 cablata, esponente di
        // densita' impostato indipendentemente a -2 in parameters.txt). Derivazione: temperatura
        // ~r^p implica velocita' del suono ~r^(p/2), scala di altezza H~r^(p/2+1.5) (Keplero fisso),
        // e con la densita' superficiale MMSN standard (~r^-1.5, valore di letteratura, non esposto
        // come parametro) la densita' di volume al midplane risulta ~r^(-3-p/2).
        double gasProfileExponent = -3.0 - params.diskTemperatureExponent / 2.0;
        double midplaneGasDensity = params.gasDensityBase * Math.pow(r3D / PhysicsConstants.AU, gasProfileExponent);
        
        double localGasDensity = midplaneGasDensity * Math.exp(-(z * z) / (2.0 * scaleHeight * scaleHeight));

        if (localGasDensity < 1e-25) return new Vector3D(0, 0, 0);

        // 3. VELOCITÀ DEL GAS SOTTO-KEPLERIANA (PRESSIONE RADIALE)
        double hOverR = scaleHeight / r3D;
        double eta = 0.5 * (hOverR * hOverR) * Math.abs(gasProfileExponent);
        
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

    public double calculateParticleEnergy(Particle p, List<Particle> allParticles)
    {
        if (!p.isAlive()) return 0.0;

        // Energia Cinetica
        double vMag = p.getVelocity().magnitude();
        double kinetic = 0.5 * p.getMass() * vMag * vMag;

        // Energia Potenziale con la Stella Centrale
        double rStar = p.getPosition().magnitude();
        double potentialStar = (rStar > 0) ? -(PhysicsConstants.G * params.centralStarMass * p.getMass()) / rStar : 0.0;

        // Energia Potenziale Mutua con le altre (divisa per 2 per evitare il doppio conteggio delle coppie)
        double potentialMutual = 0.0;
        for (Particle p2 : allParticles) {
            if (p2 == p || !p2.isAlive()) continue;
            double dist = p.getPosition().distanceTo(p2.getPosition());
            if (dist > 0) {
                potentialMutual -= (PhysicsConstants.G * p.getMass() * p2.getMass()) / dist;
            }
        }
        potentialMutual *= 0.5;

        return kinetic + potentialStar + potentialMutual;
    }

    //
    // RAGGI E GEOMETRIA DI CATTURA
    //


    /** Raggio di collisione puramente fisico: la somma dei raggi reali dei due corpi. */
    public double getPhysicalCollisionRadius(Particle p1, Particle p2) {
        return p1.getRadius() + p2.getRadius();
    }

    /**
     * Raggio di cattura per focalizzazione gravitazionale (formula di Safronov): la distanza
     * entro cui due corpi collidono davvero tenendo conto della curvatura delle traiettorie
     * indotta dalla mutua attrazione durante l'avvicinamento, non solo del contatto geometrico.
     * A parità di massa, più bassa è la velocità relativa rispetto alla velocità di fuga calcolata
     * al contatto, più il raggio effettivo si allarga oltre la semplice somma dei raggi fisici.
     */
    public double getGravitationalFocusingRadius(Particle p1, Particle p2) {
        double contactDist = getPhysicalCollisionRadius(p1, p2);
        if (contactDist <= 0.0) return 0.0;

        double totalMass = p1.getMass() + p2.getMass();
        double vEsc = Math.sqrt((2.0 * PhysicsConstants.G * totalMass) / contactDist);

        double vRel = p1.getVelocity().subtract(p2.getVelocity()).magnitude();
        if (vRel <= 0.0) return Double.POSITIVE_INFINITY; // avvicinamento a velocità nulla: cattura garantita

        double focusingFactor = Math.sqrt(1.0 + (vEsc * vEsc) / (vRel * vRel));
        return contactDist * focusingFactor;
    }

    public double getHillRadius(Particle p) {
        double r = p.getPosition().magnitude();
        return r * Math.cbrt(p.getMass() / (3.0 * params.centralStarMass));
    }


    public double getEffectiveCaptureRadius(Particle p) {
        double hillCapture = getHillRadius(p) * params.hillCaptureFraction;
        return Math.max(p.getRadius(), hillCapture);
    }

    public double getCaptureReach(Particle p) {
        double hillReach = getHillRadius(p) * params.hillAmplification;
        return Math.max(p.getRadius(), hillReach);
    }

    //
    // COLLISIONI E RISOLUZIONE
    //

    // Controllo di collisione continua (CCD) sul segmento REALMENTE percorso
    // in questo step: da previousPosition (inizio step) a position (fine step),
    // a velocità costante (coerente con l'integrazione Euler-Cromer).
    public boolean checkCollision(Particle p1, Particle p2) {
        Vector3D x1_start = p1.getPreviousPosition();
        Vector3D x2_start = p2.getPreviousPosition();

        Vector3D v1 = p1.getVelocity();
        Vector3D v2 = p2.getVelocity();

        double dt = params.dt;

        // Vettore posizione relativa iniziale e velocità relativa
        Vector3D r0 = x1_start.subtract(x2_start);
        Vector3D vRel = v1.subtract(v2);

        double a = vRel.magnitudeSquared();
        double combinedCaptureRadius = getEffectiveCaptureRadius(p1) + getEffectiveCaptureRadius(p2);
        double thresholdSq = combinedCaptureRadius * combinedCaptureRadius;

        // Se la distanza iniziale è già inferiore alla soglia
        if (r0.magnitudeSquared() <= thresholdSq) {
            return true;
        }

        if (a == 0.0) {
            return r0.magnitudeSquared() <= thresholdSq;
        }

        // Trova il tempo t di massimo avvicinamento (derivata della distanza al quadrato = 0)
        // t = - (r0 · vRel) / |vRel|^2
        double t = -r0.dotProduct(vRel) / a;

        // Limita il tempo al intervallo del timestep [0, dt] appena trascorso
        t = Math.max(0.0, Math.min(dt, t));

        // Calcola la distanza al quadrato nel momento di massimo avvicinamento t
        Vector3D rMin = r0.add(vRel.multiply(t));
        double minDistSq = rMin.magnitudeSquared();

        return minDistSq <= thresholdSq;
    }
    
    /**
     * Valuta l'esito della collisione tra due particelle selezionando tra FUSIONE, RIMBALZO o FRAMMENTAZIONE.
     */
    public CollisionResult evaluateCollision(Particle p1, Particle p2)
    {
        Vector3D relVel = p1.getVelocity().subtract(p2.getVelocity());
        double relSpeed = relVel.magnitude();
        double totalMass = p1.getMass() + p2.getMass();

        double captureRadiusSum = getEffectiveCaptureRadius(p1) + getEffectiveCaptureRadius(p2);
        double distance = Math.max(p1.getPosition().distanceTo(p2.getPosition()), captureRadiusSum);

        // Velocità di fuga reciproca dal punto di impatto
        double escapeVelocity = Math.sqrt((2.0 * PhysicsConstants.G * totalMass) / distance);

        // Soglia di FUSIONE / CATTURA
        double captureThreshold = escapeVelocity * params.gravitationalCaptureMultiplier;
        double effectiveCaptureThreshold = Math.max(params.mergeVelocityFloor, captureThreshold);

        // Soglia di FRAMMENTAZION
        double fragMultiplier = Math.max(1.0, params.fragmentationMultiplier);
        double fragmentationThreshold = effectiveCaptureThreshold * fragMultiplier;

        if (relSpeed <= effectiveCaptureThreshold) {
            return CollisionResult.MERGE;
        }
        if (relSpeed > fragmentationThreshold) {
            double massRatio = Math.min(p1.getMass(), p2.getMass()) / Math.max(p1.getMass(), p2.getMass());
            if (massRatio >= params.minFragmentationMassRatio) {
                return CollisionResult.FRAGMENT;
            }
            return CollisionResult.BOUNCE;
        }
        return CollisionResult.BOUNCE;
    }


    /**
     * Densità in funzione della massa attuale del corpo
     */
    private double densityForMass(double mass) {
        double minDensity = params.initialParticleDensity;
        double maxDensity = params.compactedMaxDensity;
        double massScale = params.initialParticleMassMax * params.compactionMassMaxMultiplier;
        return minDensity + (maxDensity - minDensity) * (1.0 - Math.exp(-mass / massScale));
    }
    /**
     * Risolve l'urto anelastico fondendo la particella 'loser' dentro la particella 'winner'.
     */
    public void mergeParticles(Particle winner, Particle loser) {
        double totalMass = winner.getMass() + loser.getMass();

        Vector3D newPos = winner.getPosition().multiply(winner.getMass())
                .add(loser.getPosition().multiply(loser.getMass()))
                .divide(totalMass);

        Vector3D newVel = winner.getVelocity().multiply(winner.getMass())
                .add(loser.getVelocity().multiply(loser.getMass()))
                .divide(totalMass);

        double newDensity = densityForMass(totalMass);

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
    public void resolveBounce(Particle p1, Particle p2, double restitution) {
        Vector3D deltaPos = p1.getPosition().subtract(p2.getPosition());
        double dist = deltaPos.magnitude();
        
        if (dist == 0) return;

        Vector3D normal = deltaPos.divide(dist);
        Vector3D relVel = p1.getVelocity().subtract(p2.getVelocity());
        double velAlongNormal = relVel.dotProduct(normal);

        // I corpi si stanno avvicinando lungo la normale di contatto (velAlongNormal < 0, dato che
        // "normal" punta da p2 verso p1): applica l'impulso solo in questo caso, altrimenti si
        // starebbero gia' allontanando e un impulso aggiungerebbe energia invece di risolvere l'urto.
        if (velAlongNormal < 0) {
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
    public List<Particle> fragmentParticles(Particle p1, Particle p2) {
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

        // 3. Generazione provvisoria delle masse e delle velocità di espulsione relative (u_i)
        double remainingMass = totalMass;
        List<Double> masses = new ArrayList<>();
        List<Double> densities = new ArrayList<>();
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

            // Densita' PROPRIA del frammento, in base alla propria massa -- non piu' la media dei
            // genitori: un frammento piccolo nasce naturalmente meno denso di uno grande, senza
            // bisogno di logica aggiuntiva, semplicemente perche' ha meno massa.
            double fragDensity = densityForMass(massFraction);
            densities.add(fragDensity);
            double estimatedRadius = Math.cbrt((3.0 * massFraction) / (4.0 * Math.PI * fragDensity));
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

            Particle frag = new Particle(positions.get(i), finalVel, m, fragmentCharge, densities.get(i));
            fragments.add(frag);
        }

        p1.setAlive(false);
        p2.setAlive(false);

        return fragments;
    }
}