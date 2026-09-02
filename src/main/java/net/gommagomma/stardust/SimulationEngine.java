package net.gommagomma.stardust;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;
import net.gommagomma.stardust.physics.Physics;
import net.gommagomma.stardust.physics.barneshut.BarnesHutTree;
import net.gommagomma.stardust.physics.collision.CollisionGrid;
import net.gommagomma.stardust.physics.collision.CollisionResult;

public class SimulationEngine {

    private final List<Particle> particles;
    private volatile boolean running = false;

    private static final ThreadLocal<List<Particle>> LOCAL_CANDIDATES = ThreadLocal.withInitial(() -> new ArrayList<>(128));
    private final List<Particle> newFragmentsBuffer = java.util.Collections.synchronizedList(new ArrayList<>());

    private final AtomicLong totalMerges = new AtomicLong(0);
    private final AtomicLong totalBounces = new AtomicLong(0);
    private final AtomicLong totalEscapes = new AtomicLong(0);
    private final AtomicLong totalStarFalls = new AtomicLong(0);
    private final AtomicLong totalFragmentations = new AtomicLong(0);

    private volatile double currentTPS = 0.0;
    private long lastTpsCheckTime = System.nanoTime();
    private long tpsStepCounter = 0;

    private double[] reach = new double[0];
    private double maxReach = 0.0;

    private double simulationTime = 0; // secondi simulati trascorsi
    private long stepCount = 0;

    private CollisionGrid collisionGrid;

    // Costruttore per una nuova simulazione
    public SimulationEngine(List<Particle> particles) {
        this.particles = particles;
    }

 // Costruttore di ripristino da savepoint
    public SimulationEngine(List<Particle> particles, double simulationTime, long stepCount, long totalMerges, long totalBounces, long totalEscapes, long totalStarFalls, long totalFragmentations)
    {
        this.particles = particles;
        this.simulationTime = simulationTime;
        this.stepCount = stepCount;
        this.totalMerges.set(totalMerges);
        this.totalBounces.set(totalBounces);
        this.totalEscapes.set(totalEscapes);
        this.totalStarFalls.set(totalStarFalls);
        this.totalFragmentations.set(totalFragmentations); // <-- Aggiunto!
    }

    public List<Particle> getParticles() { return particles; }
    public boolean isRunning() { return running; }
    public void stop() { running = false; }
    public double getSimulationTime() { return simulationTime; }
    public long getStepCount() { return stepCount; }
    public long getTotalMerges() { return totalMerges.get(); }
    public long getTotalBounces() { return totalBounces.get(); }
    public long getTotalEscapes() { return totalEscapes.get(); }
    public long getTotalStarFalls() { return totalStarFalls.get(); }
    public long getTotalFragmentations() { return totalFragmentations.get(); }
    public double getCurrentTPS() { return currentTPS; }

    public void run() {
        running = true;
        while (running) {
            step();
        }
    }

    // Un singolo passo di simulazione: forze -> integrazione -> collisioni
    public void step() {
        long t0 = System.nanoTime();

        // Forze: gravità stella + densità gas
        for (Particle p : particles) {
            p.resetForce();
            p.addForce(Physics.calculateCentralStarGravity(p));
            p.addForce(Physics.calculateDrag(p));
        }

        // Forze N-body: gravità + Elettrica
        int n = particles.size();
        if (SimulationConfig.USE_BARNES_HUT && n >= SimulationConfig.BARNES_HUT_THRESHOLD) {
            computeForcesBarnesHut();
        } else if (SimulationConfig.USE_PARALLEL_FORCES && n > 200) {
            computeForcesParallel();
        } else {
            computeForcesSequential();
        }
        long t1 = System.nanoTime();

        // Aggiornamento cinematico e condizioni ai bordi
        for (Particle p : particles) {
        	if (p.isAlive()) {
                p.update(SimulationConfig.DT);
                checkParticleBoundaries(p, SimulationConfig.STAR_RADIUS, SimulationConfig.DISK_OUTER_RADIUS * 3.0);
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
        if (SimulationConfig.LOG_SUMMARY_EVERY_N_STEPS > 0 && stepCount % SimulationConfig.LOG_SUMMARY_EVERY_N_STEPS == 0) {
            printSummary(forceMs, integrationMs, collisionMs);
        }

        // Aggiornamento per step successivo
        simulationTime += SimulationConfig.DT;
        stepCount++;
        updateTpsCounter();
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

        for (int i = 0; i < numParticles; i++) {
            Particle p1 = particles.get(i);

            for (int j = i + 1; j < numParticles; j++) {
                Particle p2 = particles.get(j);

                Vector3D fTotal = Physics.calculateGravity(p1, p2);
                if (SimulationConfig.ENABLE_ELECTROSTATIC_FORCE) {
                    Vector3D fE = Physics.calculateCoulomb(p1, p2);
                    fTotal = fTotal.add(fE);
                }

                p1.addForce(fTotal);
                p2.addForce(fTotal.multiply(-1));
            }
        }
    }

    private void computeForcesParallel() {
        java.util.stream.IntStream.range(0, particles.size()).parallel().forEach(i -> {
            Particle p1 = particles.get(i);

            double fx = 0.0, fy = 0.0, fz = 0.0;

            for (int j = 0; j < particles.size(); j++) {
                if (i == j) continue;
                Particle p2 = particles.get(j);

                Vector3D fG = Physics.calculateGravity(p1, p2);
                fx += fG.getX();
                fy += fG.getY();
                fz += fG.getZ();

                if (SimulationConfig.ENABLE_ELECTROSTATIC_FORCE) {
                    Vector3D fE = Physics.calculateCoulomb(p1, p2);
                    fx += fE.getX();
                    fy += fE.getY();
                    fz += fE.getZ();
                }
            }

            p1.addForce(new Vector3D(fx, fy, fz));
        });
    }

    private void computeForcesBarnesHut() {
        BarnesHutTree tree = new BarnesHutTree(particles, SimulationConfig.BARNES_HUT_THETA);
        int n = particles.size();
        java.util.stream.IntStream.range(0, n).parallel().forEach(i -> {
            Particle p = particles.get(i);
            p.addForce(tree.computeForce(p));
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
            double queryRadius = reach[i] + maxReach + ownSpeed * SimulationConfig.DT;

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
        for (int i = 0; i < n; i++) {
            reach[i] = Physics.getCaptureReach(particles.get(i));
            maxReach = Math.max(maxReach, reach[i]);
        }

        double targetCellSize = Math.max(maxReach, 1.0);

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
        if (!Physics.checkCollision(p1, p2)) return;

        // Lock ordinati per ID per prevenire deadlock
        Particle firstLock  = (p1.getId() < p2.getId()) ? p1 : p2;
        Particle secondLock = (p1.getId() < p2.getId()) ? p2 : p1;

        synchronized (firstLock) {
            synchronized (secondLock) {
                //  Un altro thread potrebbe aver inghiottito p1 o p2 un istante fa.
                if (!p1.isAlive() || !p2.isAlive()) return;

                // Verifica che stiano ANCORA collidendo ora che abbiamo lo stato bloccato.
                if (!Physics.checkCollision(p1, p2)) return;
                 
                 // Valutazione esito collisione a tre vie
                 // Forza la fusione se la velocità relativa è troppo bassa per sostenere un rimbalzo stabile
                double relSpeed = p1.getVelocity().subtract(p2.getVelocity()).magnitude();
                CollisionResult result = (relSpeed <3) ? CollisionResult.MERGE : Physics.evaluateCollision(p1, p2);
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

        Physics.mergeParticles(winner, loser);

        long mergeId = totalMerges.incrementAndGet();

        if (SimulationConfig.LOG_ACCRETION_EVENTS && bothAggregated) {
        	System.out.printf(
        			"[t=%12.1fs] IMPATTO #%d: #%d (m=%.3e kg) + #%d (m=%.3e kg) -> #%d (m=%.3e kg, r=%.3e m)%n",
        			simulationTime, mergeId, winner.getId(), winnerInitialMass, loser.getId(), loserInitialMass,
        			winner.getId(), winner.getMass(), winner.getRadius());
        } else if (wasAggregated) {
        	System.out.printf(
        			"[t=%12.1fs] CANNIBALISMO #%d: #%d (m=%.3e kg) + #%d (m=%.3e kg) -> #%d (m=%.3e kg, r=%.3e m)%n",
        			simulationTime, mergeId, winner.getId(), winnerInitialMass, loser.getId(), loserInitialMass,
        			winner.getId(), winner.getMass(), winner.getRadius());
        } else {
        	System.out.printf(
        			"[t=%12.1fs] ACCRESCIMENTO #%d: #%d (m=%.3e kg) + #%d (m=%.3e kg) -> #%d (m=%.3e kg, r=%.3e m)%n",
        			simulationTime, mergeId, winner.getId(), winnerInitialMass, loser.getId(), loserInitialMass,
        			winner.getId(), winner.getMass(), winner.getRadius());
        }
    }

    private void handleFragmentation(Particle p1, Particle p2) {
        long fragId = totalFragmentations.incrementAndGet();

        // Genera i frammenti
        List<Particle> fragments = Physics.fragmentParticles(p1, p2);

        newFragmentsBuffer.addAll(fragments);

        if (SimulationConfig.LOG_BOUNCE_EVENTS) { // o flag equivalente per logging
            double relSpeed = p1.getVelocity().subtract(p2.getVelocity()).magnitude();
            System.out.printf(
                "[t=%12.1fs] FRAMMENTAZIONE #%d: #%d (m=%.2e kg) + #%d (m=%.2e kg) -> Generati %d frammenti [v_rel=%.1f m/s]%n",
                simulationTime, fragId, p1.getId(), p1.getMass(), p2.getId(), p2.getMass(), fragments.size(), relSpeed
            );
        }
    }
    
    private void handleBounce(Particle p1, Particle p2) {
        Physics.resolveBounce(p1, p2, 0.5);
        long bounceId = totalBounces.incrementAndGet();

        if (SimulationConfig.LOG_BOUNCE_EVENTS) {
            double relSpeed = p1.getVelocity().subtract(p2.getVelocity()).magnitude();
            double dist = p1.getPosition().distanceTo(p2.getPosition());
            System.out.printf(
                "[t=%12.1fs] RIMBALZO #%d: #%d <-> #%d [v_rel=%.1f m/s, dist=%.1f m]%n",
                simulationTime, bounceId, p1.getId(), p2.getId(), relSpeed, dist
            );
        }
    }

    private void checkParticleBoundaries(Particle p, double starRadius, double maxSystemRadius) {
        if (!p.isAlive()) return;

        double distFromCenter = p.getPosition().magnitude();

        // CATTURA DA PARTE DELLA STELLA
        if (distFromCenter <= starRadius) {
            p.setAlive(false);
            totalStarFalls.incrementAndGet();
            if (SimulationConfig.LOG_ACCRETION_EVENTS) {
                System.out.printf("[t=%12.1fs] CADUTA NELLA STELLA: Particella #%d (m=%.2e kg)%n", simulationTime, p.getId(), p.getMass());
            }
        }
        // ESPULSIONE DAL SISTEMA SOLARE
        else if (distFromCenter > maxSystemRadius) {
            double vEsc = Math.sqrt((2.0 * SimulationConfig.G * SimulationConfig.STAR_MASS) / distFromCenter);
            if (p.getVelocity().magnitude() > vEsc) {
                p.setAlive(false);
                totalEscapes.incrementAndGet();
                if (SimulationConfig.LOG_ACCRETION_EVENTS) {
                    System.out.printf("[t=%12.1fs] FUGA INTERSTELLARE: Particella #%d schizzata via dal sistema!%n", simulationTime, p.getId());
                }
            }
        }
    }

    private void printSummary(double forceMs, double integrationMs, double collisionMs) {
        double totalMass = 0;
        double maxMass = 0;
        double maxRadius = 0;
        double totalEnergy = 0;
        long aliveCount = 0;

        for (Particle p : particles) {
        	if (p.isAlive()) {
                aliveCount++;
                //totalEnergy += Physics.calculateParticleEnergy(p, particles); // molto lento!!!
                totalMass += p.getMass();
                if (p.getMass() > maxMass) {
                    maxMass = p.getMass();
                    maxRadius = p.getRadius();
                }
               
            }
        }

        System.out.printf(
                "[t=%12.1fs] ENERGIA TOT: %.8e J | STATO: %d particelle | massa tot=%.4e kg | massa max=%.4e kg | raggio max=%.4e m | fusioni=%d | rimbalzi=%d | frammentazioni=%d | cadute=%d | fughe=%d | Forze: %.2f ms | Integrazioni: %.2f ms | Collisioni: %.2f ms%n",
                simulationTime, totalEnergy, aliveCount, totalMass, maxMass, maxRadius, 
                totalMerges.get(), totalBounces.get(), totalFragmentations.get(), totalStarFalls.get(), totalEscapes.get(), 
                forceMs, integrationMs, collisionMs);
    }
}