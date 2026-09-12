package net.gommagomma.stardust;

import java.util.ArrayList;
import java.util.List;

import net.gommagomma.stardust.io.RunLogger;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;
import net.gommagomma.stardust.physics.Physics;
import net.gommagomma.stardust.physics.barneshut.BarnesHutTree;
import net.gommagomma.stardust.physics.collision.CollisionGrid;
import net.gommagomma.stardust.physics.collision.CollisionResult;

public class SimulationEngine {
	private final SimulationParams params;
	private final Physics physics;
	private final CourantMonitor courantMonitor = new CourantMonitor();
    private final List<Particle> particles;
    private final SimulationMetrics metrics;
    private final RunLogger logger;
    private volatile boolean running = false;

    private static final ThreadLocal<List<Particle>> LOCAL_CANDIDATES = ThreadLocal.withInitial(() -> new ArrayList<>(128));
    private final List<Particle> newFragmentsBuffer = java.util.Collections.synchronizedList(new ArrayList<>());

    private volatile double currentTPS = 0.0;
    private long lastTpsCheckTime = System.nanoTime();
    private long tpsStepCounter = 0;

    private double[] reach = new double[0];
    private double maxReach = 0.0;
    
    private CollisionGrid collisionGrid;

    private volatile boolean paused = false;

    public boolean isPaused() { return paused; }
    public void setPaused(boolean paused) { this.paused = paused; }
    public void togglePause() { this.paused = !this.paused; }

    public CourantMonitor getCourantMonitor() { return courantMonitor; }
    
    // Costruttore per una nuova simulazione
    public SimulationEngine(List<Particle> particles, RunLogger logger, SimulationParams params) {
        this.particles = particles;
        this.params = params;
        this.metrics = new SimulationMetrics();
        this.logger = logger;
        this.physics = new Physics(params);
    }

    // Costruttore di ripristino da savepoint
    public SimulationEngine(List<Particle> particles, RunLogger logger, SimulationMetrics metrics, SimulationParams params)
    {
        this.particles = particles;
        this.params = params;
        this.logger = logger;
        this.metrics = metrics;
        this.physics = new Physics(params);
    }

    public List<Particle> getParticles() { return particles; }
    public SimulationMetrics getMetrics() { return metrics; }
    public boolean isRunning() { return running; }
    public void stop() { running = false; }
    public double getCurrentTPS() { return currentTPS; }

    public void run() {
        running = true;
        while (running) {
            if (paused) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                continue;
            }
            step();
        }
    }

    // Un singolo passo di simulazione: forze -> integrazione -> collisioni
    public void step() {
        long t0 = System.nanoTime();

        courantMonitor.setNominalDt(params.dt); // salva il dt configurato

        // Forze: gravità stella + densità gas
        for (Particle p : particles) {
            p.resetForce();
            p.addForce(physics.calculateCentralStarGravity(p));
            p.addForce(physics.calculateDrag(p));
        }

        // Forze N-body: gravità + Elettrostatica + calcolo del Courant
        int n = particles.size();
        if (params.useBarnesHut && n >= params.barnesHutThreshold) {
            computeForcesBarnesHut();
        } else if (params.useParallelForces && n > params.parallelForcesThreshold) {
            computeForcesParallel();
        } else {
            computeForcesSequential();
        }
        long t1 = System.nanoTime();

        // Timestep adattivo: il Courant appena calcolato (nelle forze) dice se
        // questo step, integrato per intero, sarebbe troppo grezzo per l'incontro più ravvicinato
        // visto durante le forze.
        double nominalDt = courantMonitor.getNominalDt();
        double effectiveDt = nominalDt;
        double courantThisStep = courantMonitor.getMax();
        boolean dtWasReduced = false;
        if (courantThisStep > params.courantSafetyThreshold) {
            double scale = params.courantSafetyThreshold / courantThisStep;
            effectiveDt = Math.max(nominalDt * scale, nominalDt * params.minDtFraction);
            dtWasReduced = (effectiveDt < nominalDt);
            courantMonitor.recordReduction(effectiveDt, courantThisStep, courantMonitor.getMaxPairId1(), courantMonitor.getMaxPairId2());
        }
        params.dt = effectiveDt;

        // Aggiornamento cinematico e condizioni ai bordi
        for (Particle p : particles) {
        	if (p.isAlive()) {
                p.update(params.dt);
                checkParticleBoundaries(p, params.centralStarRadius, params.diskOuterRadius * 3.0);
            }
        }
        long t2 = System.nanoTime();

        // Controllo Collisioni e Fusione
        synchronized (particles) {
            handleCollisions();
        }
        long t3 = System.nanoTime();

        // Logging
        double forceMs = (t1 - t0) / 1_000_000.0;
        double integrationMs = (t2 - t1) / 1_000_000.0;
        double collisionMs  = (t3 - t2) / 1_000_000.0;
        if (params.logSummaryEveryNSteps > 0 && metrics.getStepCount() % params.logSummaryEveryNSteps == 0) {
            printSummary(forceMs, integrationMs, collisionMs, effectiveDt, dtWasReduced);
            courantMonitor.resetSummaryWindow(); // la finestra aggregata riparte per il prossimo intervallo
        }

        // Aggiornamento per step successivo: il tempo simulato avanza della quantitaà realmente
        // usata in questo step.
        metrics.addTime(effectiveDt);
        metrics.incrementStep();
        updateTpsCounter();

        // Ripristina il valore nominale prima di uscire dallo step.
        params.dt = nominalDt;
    }

    private void updateTpsCounter() {
        tpsStepCounter++;
        long now = System.nanoTime();
        long elapsed = now - lastTpsCheckTime;

        if (elapsed >= 1_000_000_000L) {
            currentTPS = (tpsStepCounter * 1_000_000_000.0) / elapsed;
            tpsStepCounter = 0;
            lastTpsCheckTime = now;
        }
    }

    private void computeForcesSequential() {
        int numParticles = particles.size();
        courantMonitor.reset();

        double[] reachLocal = new double[numParticles];
        for (int i = 0; i < numParticles; i++) {
            Particle p = particles.get(i);
            p.resetPotentialEnergy();
            reachLocal[i] = physics.getCaptureReach(p); // calcolato una volta per particella
        }

        for (int i = 0; i < numParticles; i++) {
            Particle p1 = particles.get(i);

            for (int j = i + 1; j < numParticles; j++) {
                Particle p2 = particles.get(j);
                Vector3D fTotal = physics.calculateGravityAndElectrostaticForce(p1, p2);

                p1.addForce(fTotal);
                p2.addForce(fTotal.multiply(-1));
                
                double dist = p1.getPosition().distanceTo(p2.getPosition());
                if (dist > 0) {
                    double pot = -(PhysicsConstants.G * p1.getMass() * p2.getMass()) / dist;
                    p1.addPotentialEnergy(pot);
                    p2.addPotentialEnergy(pot);
                }

                // Calcolo Courant
                if (dist <= reachLocal[i] + reachLocal[j]) {
                    double sumRadii = p1.getRadius() + p2.getRadius();
                    if (sumRadii > 0) {
                        double relSpeed = p1.getVelocity().subtract(p2.getVelocity()).magnitude();
                        courantMonitor.update((relSpeed * params.dt) / sumRadii, p1.getId(), p2.getId());
                    }
                }
            }
        }
    }

    private void computeForcesParallel() {
        int numParticles = particles.size();
    	courantMonitor.reset();

        double[] reachLocal = new double[numParticles];
        java.util.stream.IntStream.range(0, numParticles).parallel().forEach(i -> {
            Particle p = particles.get(i);
            p.resetPotentialEnergy();
            reachLocal[i] = physics.getCaptureReach(p);
        });
    	
        java.util.stream.IntStream.range(0, numParticles).parallel().forEach(i -> {
            double fx = 0.0, fy = 0.0, fz = 0.0;
            double potentialSum = 0.0;
            double localMaxCourant = 0.0; // accumulo locale al thread: un solo update() atomico a fine ciclo
            int localMaxOtherId = -1;

            Particle p1 = particles.get(i);

            for (int j = 0; j < numParticles; j++) {
                if (i == j) continue;
                Particle p2 = particles.get(j);
                Vector3D f = physics.calculateGravityAndElectrostaticForce(p1, p2);
                fx += f.getX();
                fy += f.getY();
                fz += f.getZ();
                
                // calcolo del potenziale
                double dist = p1.getPosition().distanceTo(p2.getPosition());
                if (dist > 0) {
                    potentialSum -= (PhysicsConstants.G * p1.getMass() * p2.getMass()) / dist;
                }

                double sumRadii = p1.getRadius() + p2.getRadius();
                if (sumRadii > 0 && dist <= reachLocal[i] + reachLocal[j]) {
                    double relSpeed = p1.getVelocity().subtract(p2.getVelocity()).magnitude();
                    double courant = (relSpeed * params.dt) / sumRadii;
                    if (courant > localMaxCourant) {
                        localMaxCourant = courant;
                        localMaxOtherId = p2.getId();
                    }
                }
            }

            p1.addForce(new Vector3D(fx, fy, fz));
            p1.addPotentialEnergy(potentialSum);
            courantMonitor.update(localMaxCourant, p1.getId(), localMaxOtherId);
        });
    }

    private void computeForcesBarnesHut() {
        BarnesHutTree tree = new BarnesHutTree(particles, params, physics);
        courantMonitor.reset();
        int n = particles.size();
        java.util.stream.IntStream.range(0, n).parallel().forEach(i -> {
            Particle p = particles.get(i);
            p.resetPotentialEnergy();
            p.addForce(tree.computeForce(p, courantMonitor));
        });
    }

    private void handleCollisions() {
        int n = particles.size();
        if (n == 0) return;

        newFragmentsBuffer.clear();
        
        // Preparazione griglia spaziale
        updateCollisionGrid(n);

        // Ricerca candidati e risoluzione in parallelo
        java.util.stream.IntStream.range(0, n).parallel().forEach(i -> {
            Particle p1 = particles.get(i);
            if (!p1.isAlive()) return;

            List<Particle> localCandidates = LOCAL_CANDIDATES.get();
            localCandidates.clear();

            double ownSpeed = p1.getVelocity().magnitude();
            double queryRadius = reach[i] + maxReach + ownSpeed * params.dt;

            collisionGrid.queryNeighbors(p1.getPosition(), queryRadius, localCandidates);

            for (Particle p2 : localCandidates) {
                if (p2 == p1 || p2.getId() <= p1.getId() || !p2.isAlive()) continue;

                processCollision(p1, p2);

                if (!p1.isAlive()) break; // Se p1 è stata assorbita o frammentata, interrompi
            }
        });

        // Rimuove le particelle morte (genitori frammentati o inglobati)
        particles.removeIf(p -> !p.isAlive());

        // Inserisce i nuovi frammenti generati nel buffer in modo atomico
        if (!newFragmentsBuffer.isEmpty()) {
            synchronized (newFragmentsBuffer) {
                particles.addAll(newFragmentsBuffer);
                newFragmentsBuffer.clear();
            }
        }
    }

    private void updateCollisionGrid(int n) {
        if (reach.length < n) {
            reach = new double[n];
        }

        maxReach = 0.0;
        double maxDisplacement = 0.0; // il termine ownSpeed*dt usato nel queryRadius sotto -- deve
                                       // contribuire alla dimensione della cella, non solo maxReach,
                                       // altrimenti una particella veloce puo' far esplodere in modo
                                       // cubico il numero di celle scandagliate da queryNeighbors
                                       // (cellRadius = ceil(queryRadius/cellSize)).
        for (int i = 0; i < n; i++) {
            Particle p = particles.get(i);
            reach[i] = physics.getCaptureReach(p);
            maxReach = Math.max(maxReach, reach[i]);
            maxDisplacement = Math.max(maxDisplacement, p.getVelocity().magnitude() * params.dt);
        }

        // Dimensionata cosi', la cella e' sempre confrontabile con il piu' grande queryRadius
        // possibile (reach[i] + maxReach + ownSpeed*dt <= 2*maxReach + maxDisplacement): cellRadius
        // resta quindi limitato a un piccolo numero costante di celle, a prescindere da quanto
        // sono veloci le particelle o piccolo il raggio di Hill in gioco.
        double targetCellSize = Math.max(maxReach + maxDisplacement, 1.0);

        if (collisionGrid == null || Math.abs(collisionGrid.getCellSize() - targetCellSize) > 0.1) {
            collisionGrid = new CollisionGrid(targetCellSize);
        } else {
            collisionGrid.clear();
        }

        for (Particle p : particles) {
            collisionGrid.insert(p);
        }
    }

    private void processCollision(Particle p1, Particle p2) {
        if (!physics.checkCollision(p1, p2)) return;

        // Lock ordinati per ID per prevenire deadlock
        Particle firstLock  = (p1.getId() < p2.getId()) ? p1 : p2;
        Particle secondLock = (p1.getId() < p2.getId()) ? p2 : p1;

        synchronized (firstLock) {
            synchronized (secondLock) {
                //  Un altro thread potrebbe aver inghiottito p1 o p2 un istante fa.
                if (!p1.isAlive() || !p2.isAlive()) return;

                // Verifica che stiano ANCORA collidendo ora che abbiamo lo stato bloccato.
                if (!physics.checkCollision(p1, p2)) return;
                 
                 // Valutazione esito collisione a tre vie
                 // Forza la fusione se la velocità relativa è troppo bassa per sostenere un rimbalzo stabile
                double relSpeed = p1.getVelocity().subtract(p2.getVelocity()).magnitude();

                CollisionResult result = (relSpeed <3) ? CollisionResult.MERGE : physics.evaluateCollision(p1, p2);
                switch (result) {
                    case MERGE:
                        handleAccretion(p1, p2);
                        break;
                    case FRAGMENT:
                        handleFragmentation(p1, p2);
                        break;
                    case BOUNCE:
                    default:
                        handleBounce(p1, p2);
                        break;
                }
            }
        }
    }

    private void handleAccretion(Particle p1, Particle p2) {
        Particle winner = (p1.getMass() >= p2.getMass()) ? p1 : p2;
        Particle loser  = (winner == p1) ? p2 : p1;

        double winnerInitialMass = winner.getMass();
        double loserInitialMass = loser.getMass();
        boolean wasAggregated = p1.isAggregated() || p2.isAggregated();
        boolean bothAggregated = p1.isAggregated() && p2.isAggregated();

        physics.mergeParticles(winner, loser);

        long mergeId = metrics.recordMerge();

        if (bothAggregated) {
        	logger.log(String.format(
        			"[t=%12.1fs] IMPATTO #%d: #%d (m=%.3e kg) + #%d (m=%.3e kg) -> #%d (m=%.3e kg, r=%.3e m)",
        			metrics.getSimulationTime(), mergeId, winner.getId(), winnerInitialMass, loser.getId(), loserInitialMass,
        			winner.getId(), winner.getMass(), winner.getRadius()));
        } else if (wasAggregated) {
        	logger.log(String.format(
        			"[t=%12.1fs] CANNIBALISMO #%d: #%d (m=%.3e kg) + #%d (m=%.3e kg) -> #%d (m=%.3e kg, r=%.3e m)",
        			metrics.getSimulationTime(), mergeId, winner.getId(), winnerInitialMass, loser.getId(), loserInitialMass,
        			winner.getId(), winner.getMass(), winner.getRadius()));
        } else {
        	logger.log(String.format(
        			"[t=%12.1fs] ACCRESCIMENTO #%d: #%d (m=%.3e kg) + #%d (m=%.3e kg) -> #%d (m=%.3e kg, r=%.3e m)",
        			metrics.getSimulationTime(), mergeId, winner.getId(), winnerInitialMass, loser.getId(), loserInitialMass,
        			winner.getId(), winner.getMass(), winner.getRadius()));
        }
    }

    private void handleFragmentation(Particle p1, Particle p2) {
        long fragId = metrics.recordFragmentation();

        // Genera i frammenti
        List<Particle> fragments = physics.fragmentParticles(p1, p2);

        newFragmentsBuffer.addAll(fragments);

        double relSpeed = p1.getVelocity().subtract(p2.getVelocity()).magnitude();
        logger.log(String.format(
            "[t=%12.1fs] FRAMMENTAZIONE #%d: #%d (m=%.2e kg) + #%d (m=%.2e kg) -> Generati %d frammenti [v_rel=%.1f m/s]",
            metrics.getSimulationTime(), fragId, p1.getId(), p1.getMass(), p2.getId(), p2.getMass(), fragments.size(), relSpeed
        ));
    }
    
    private void handleBounce(Particle p1, Particle p2) {
    	physics.resolveBounce(p1, p2, 0.5);
        long bounceId = metrics.recordBounce();

        double relSpeed = p1.getVelocity().subtract(p2.getVelocity()).magnitude();
        double dist = p1.getPosition().distanceTo(p2.getPosition());
        logger.log(String.format(
        		"[t=%12.1fs] RIMBALZO #%d: #%d <-> #%d [v_rel=%.1f m/s, dist=%.1f m]",
        		metrics.getSimulationTime(), bounceId, p1.getId(), p2.getId(), relSpeed, dist
        ));
    }

    private void checkParticleBoundaries(Particle p, double starRadius, double maxSystemRadius) {
        if (!p.isAlive()) return;

        double distFromCenter = p.getPosition().magnitude();

        // CATTURA DA PARTE DELLA STELLA
        if (distFromCenter <= starRadius) {
            p.setAlive(false);
            metrics.recordStarFall();
            logger.log(String.format("[t=%12.1fs] CADUTA NELLA STELLA: Particella #%d (m=%.2e kg)", metrics.getSimulationTime(), p.getId(), p.getMass()));
        }
        // ESPULSIONE DAL SISTEMA SOLARE
        else if (distFromCenter > maxSystemRadius) {
            double vEsc = Math.sqrt((2.0 * PhysicsConstants.G * params.centralStarMass) / distFromCenter);
            if (p.getVelocity().magnitude() > vEsc) {
                p.setAlive(false);
                metrics.recordEscape();
                logger.log(String.format("[t=%12.1fs] FUGA INTERSTELLARE: Particella #%d schizzata via dal sistema!", metrics.getSimulationTime(), p.getId()));
            }
        }
    }

    private void printSummary(double forceMs, double integrationMs, double collisionMs, double effectiveDt, boolean dtWasReduced) {
        double totalMass = 0;
        double maxMass = 0;
        double maxRadius = 0;
        double totalStarPotentialEnergy = 0;
        double totalPotentialEnergy = 0;
        double totalKineticEnergy = 0;
        long aliveCount = 0;

        for (Particle p : particles) {
            if (p.isAlive()) {
                aliveCount++;
                totalPotentialEnergy += p.getPotentialEnergy();
                totalStarPotentialEnergy += physics.calculateCentralStarPotentialEnergy(p);
                
                // Calcolo energia cinetica: 0.5 * m * v^2
                // v^2 è la norma al quadrato del vettore velocità (vx*vx + vy*vy + vz*vz)
                Vector3D vel = p.getVelocity();
                double speedSq = vel.getX() * vel.getX() + vel.getY() * vel.getY() + vel.getZ() * vel.getZ();
                totalKineticEnergy += 0.5 * p.getMass() * speedSq;

                totalMass += p.getMass();
                if (p.getMass() > maxMass) {
                    maxMass = p.getMass();
                    maxRadius = p.getRadius();
                }
            }
        }

        // Dividiamo per 2 il potenziale per evitare il doppio conteggio delle coppie
        double totalMechanicalEnergy = totalKineticEnergy + totalPotentialEnergy / 2.0 + totalStarPotentialEnergy;

        // La coppia responsabile del Courant massimo viene riportata SOLO quando dt e' stato
        // ridotto: negli step normali il valore e' quasi sempre 0 (nessuna coppia vicina abbastanza
        // da contare), riportare -1/-1 ad ogni riga sarebbe solo rumore nel log.
        String pairInfo = dtWasReduced
                ? String.format(" [coppia #%d-#%d]", courantMonitor.getMaxPairId1(), courantMonitor.getMaxPairId2())
                : "";

        // Riepilogo AGGREGATO dall'ultimo summary: quante riduzioni sono avvenute nell'intervallo
        // appena trascorso (non solo in questo singolo step), e qual e' stata la peggiore -- senza
        // questo, un episodio di riduzione sostenuto tra due summary può restare completamente
        // invisibile se il singolo step campionato non era tra quelli ridotti.
        int reductionsSinceLastSummary = courantMonitor.getReductionsSinceLastSummary();
        String intervalInfo = (reductionsSinceLastSummary > 0)
                ? String.format(" | riduzioni dall'ultimo summary=%d (peggiore: Courant=%.2f, dt=%.1fs, coppia #%d-#%d)",
                        reductionsSinceLastSummary, courantMonitor.getWorstCourantSinceLastSummary(),
                        courantMonitor.getWorstEffectiveDtSinceLastSummary(),
                        courantMonitor.getWorstPairId1SinceLastSummary(), courantMonitor.getWorstPairId2SinceLastSummary())
                : "";

        logger.log(String.format(
                "[t=%13.1fs] ENERGIA: %.8e J | STATO: %d particelle | Courant: %.2f%s | dt: %.1fs%s%s | massa tot=%.4e kg | massa max=%.4e kg | raggio max=%.4e m | fusioni=%d | rimbalzi=%d | frammentazioni=%d | cadute=%d | fughe=%d | Forze: %.2f ms | Integrazioni: %.2f ms | Collisioni: %.2f ms",
                metrics.getSimulationTime(), totalMechanicalEnergy,
                aliveCount, courantMonitor.getMax(), pairInfo, effectiveDt, (dtWasReduced ? " [RIDOTTO]" : ""), intervalInfo, totalMass, maxMass, maxRadius, 
                metrics.getTotalMerges(), metrics.getTotalBounces(), metrics.getTotalFragmentations(), metrics.getTotalStarFalls(), metrics.getTotalEscapes(), 
                forceMs, integrationMs, collisionMs));
    }
}