package net.gommagomma.stardust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.gommagomma.stardust.io.RunLogger;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;

/**
 * Test end-to-end su SimulationEngine.step(): a differenza dei test su Physics/GravityCalculator
 * (che validano una singola formula isolata), qui si esercita l'engine COME OGGETTO INTERO —
 * il dispatch reale sequenziale/parallelo/Barnes-Hut in base a N, il multithreading dentro
 * handleCollisions (IntStream.parallel(), i lock ordinati per ID), e l'interazione tra
 * forze+integrazione+collisioni nello stesso step. Sono gli unici test della suite che possono
 * scovare un bug di concorrenza reale (race condition, doppio conteggio) che un test su una
 * singola chiamata a Physics non potrebbe mai vedere.
 */
class SimulationEngineTest {

    private RunLogger newLogger(Path tempDir, String name) throws IOException {
        return new RunLogger(tempDir.resolve(name + ".log"));
    }

    private static Particle particleAt(double x, double y, double z, double vx, double vy, double vz, double mass) {
        return new Particle(new Vector3D(x, y, z), new Vector3D(vx, vy, vz), mass, 0.0, 3000.0);
    }

    /** Nube di particelle su un disco realistico (0.3-0.7 AU), velocità circolari kepleriane
     *  approssimate: abbastanza "ben educata" da non far cadere nulla nella stella né fuggire
     *  entro poche decine di step, ma abbastanza densa da generare vere collisioni. */
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

            double mass = params.baseParticleMassMin
                    + rnd.nextDouble() * (params.baseParticleMassMax - params.baseParticleMassMin);

            particles.add(particleAt(x, y, 0, vx, vy, 0, mass));
        }
        return particles;
    }

    /**
     * Cluster LOCALE e denso attorno a un unico punto dell'orbita (raggio r0 dalla stella,
     * cosi' il raggio di Hill -- che dipende dalla distanza dalla stella -- resta realistico),
     * con le particelle sparse in un volume molto piu' piccolo del raggio di cattura combinato:
     * a differenza di generateOrbitingCloud (pensata per essere fisicamente "ben educata" su
     * tutto il disco, dove la distanza tipica tra vicini e' ordini di grandezza piu' grande del
     * raggio di cattura), questo cluster garantisce che molte coppie siano gia' in collisione
     * fin dal primissimo step.
     */
    private static List<Particle> generateDenseLocalCluster(int n, SimulationParams params, long seed) {
        Random rnd = new Random(seed);
        List<Particle> particles = new ArrayList<>(n);

        double r0 = 0.5 * (params.diskInnerRadius + params.diskOuterRadius);
        double v0 = Math.sqrt(PhysicsConstants.G * params.centralStarMass / r0);
        double clusterRadius = 2.0e6; // m: molto piu' piccolo del raggio di cattura tipico (decine di migliaia di km)

        for (int i = 0; i < n; i++) {
            double dx = (rnd.nextDouble() - 0.5) * 2 * clusterRadius;
            double dy = (rnd.nextDouble() - 0.5) * 2 * clusterRadius;
            double mass = params.baseParticleMassMin
                    + rnd.nextDouble() * (params.baseParticleMassMax - params.baseParticleMassMin);

            particles.add(particleAt(r0 + dx, dy, 0, 0, v0, 0, mass));
        }
        return particles;
    }

    // ---------------------------------------------------------------
    // Conservazione della massa attraverso i tre percorsi di dispatch
    // ---------------------------------------------------------------

    @Test
    void step_conservesTotalMass_overManySteps_viaSequentialDispatch(@TempDir Path tempDir) throws IOException {
        SimulationParams params = new SimulationParams();
        params.n = 50; // sotto qualunque soglia: usa computeForcesSequential

        List<Particle> particles = generateOrbitingCloud(50, params, 1L);
        double totalMassBefore = particles.stream().mapToDouble(Particle::getMass).sum();

        SimulationEngine engine = new SimulationEngine(particles, newLogger(tempDir, "seq"), params);
        for (int i = 0; i < 100; i++) {
            engine.step();
        }

        double totalMassAfter = engine.getParticles().stream().mapToDouble(Particle::getMass).sum();
        assertEquals(totalMassBefore, totalMassAfter, totalMassBefore * 1e-9,
                "La massa totale deve conservarsi esattamente attraverso il dispatch sequenziale");
    }

    @Test
    void step_conservesTotalMass_overManySteps_viaParallelDispatch(@TempDir Path tempDir) throws IOException {
        SimulationParams params = new SimulationParams();
        params.n = 250;
        params.barnesHutThreshold = 100000; // forza il fallback parallelo anche a 250 particelle

        List<Particle> particles = generateOrbitingCloud(250, params, 2L);
        double totalMassBefore = particles.stream().mapToDouble(Particle::getMass).sum();

        SimulationEngine engine = new SimulationEngine(particles, newLogger(tempDir, "par"), params);
        for (int i = 0; i < 50; i++) {
            engine.step();
        }

        double totalMassAfter = engine.getParticles().stream().mapToDouble(Particle::getMass).sum();
        assertEquals(totalMassBefore, totalMassAfter, totalMassBefore * 1e-9,
                "La massa totale deve conservarsi esattamente attraverso il dispatch parallelo (computeForcesParallel)");
    }

    @Test
    void step_conservesTotalMass_overManySteps_viaBarnesHutDispatch(@TempDir Path tempDir) throws IOException {
        SimulationParams params = new SimulationParams();
        params.n = 450;
        params.barnesHutThreshold = 400; // 450 >= 400: attiva Barnes-Hut

        List<Particle> particles = generateOrbitingCloud(450, params, 3L);
        double totalMassBefore = particles.stream().mapToDouble(Particle::getMass).sum();

        SimulationEngine engine = new SimulationEngine(particles, newLogger(tempDir, "bh"), params);
        for (int i = 0; i < 50; i++) {
            engine.step();
        }

        double totalMassAfter = engine.getParticles().stream().mapToDouble(Particle::getMass).sum();
        assertEquals(totalMassBefore, totalMassAfter, totalMassBefore * 1e-9,
                "La massa totale deve conservarsi esattamente attraverso il dispatch Barnes-Hut");
    }

    // ---------------------------------------------------------------
    // Coerenza tra i contatori delle collisioni (multithread) e l'evoluzione di N
    // ---------------------------------------------------------------

    @Test
    void handleCollisions_particleCountEvolution_isConsistentWith_mergeAndFragmentationCounters(@TempDir Path tempDir) throws IOException {
        // Cluster locale denso (vedi generateDenseLocalCluster) apposta per generare parecchie
        // collisioni reali fin dai primissimi step, cosi' da esercitare davvero handleCollisions()
        // sotto il carico multithread (IntStream.parallel(), lock ordinati per ID) e verificare
        // che il conteggio delle particelle risultante torni ESATTAMENTE con quello che dicono
        // i contatori cumulativi -- lo stesso tipo di riscontro incrociato ID<->STATO che abbiamo
        // usato più volte analizzando i log reali di produzione.
        SimulationParams params = new SimulationParams();
        params.n = 300;
        params.dt = 300.0;

        List<Particle> particles = generateDenseLocalCluster(300, params, 4L);
        int nInitial = particles.size();

        SimulationEngine engine = new SimulationEngine(particles, newLogger(tempDir, "collisions"), params);

        long fusioniBefore = engine.getMetrics().getTotalMerges();
        long frammentazioniBefore = engine.getMetrics().getTotalFragmentations();

        for (int i = 0; i < 10; i++) {
            engine.step();
        }

        long fusioniAfter = engine.getMetrics().getTotalMerges();
        long frammentazioniAfter = engine.getMetrics().getTotalFragmentations();
        int nFinal = engine.getParticles().size();

        long merges = fusioniAfter - fusioniBefore;
        long fragmentations = frammentazioniAfter - frammentazioniBefore;

        assertTrue(merges > 0 || fragmentations > 0,
                "Con un cluster cosi' denso ci si aspetta almeno qualche fusione o frammentazione nei primi step");

        // Ogni fusione riduce N di 1 (loser assorbito). Ogni frammentazione DISTRUGGE 2 genitori e ne
        // genera tra 2 e 5: non conosciamo il numero esatto di frammenti per singolo evento senza
        // parsare il log, ma possiamo verificare i LIMITI teorici della variazione di N.
        int minPossibleFragmentDelta = 0;                    // minimo 2 frammenti, -2 genitori = 0
        int maxPossibleFragmentDelta = (int) fragmentations * 3; // massimo 5 frammenti, -2 genitori = +3

        int expectedNMin = nInitial - (int) merges + minPossibleFragmentDelta;
        int expectedNMax = nInitial - (int) merges + maxPossibleFragmentDelta;

        assertTrue(nFinal >= expectedNMin && nFinal <= expectedNMax,
                "N finale (" + nFinal + ") fuori dai limiti teorici [" + expectedNMin + ", " + expectedNMax
                        + "] dati N iniziale=" + nInitial + ", fusioni=" + merges + ", frammentazioni=" + fragmentations);
    }

    // ---------------------------------------------------------------
    // Metriche: step/tempo simulato devono avanzare esattamente come atteso
    // ---------------------------------------------------------------

    @Test
    void metrics_stepCountAndSimulationTime_advanceExactly_withEachStep(@TempDir Path tempDir) throws IOException {
        SimulationParams params = new SimulationParams();
        params.n = 30;
        params.dt = 123.0; // valore non tondo, per essere sicuri che non sia un caso fortunato

        List<Particle> particles = generateOrbitingCloud(30, params, 5L);
        SimulationEngine engine = new SimulationEngine(particles, newLogger(tempDir, "metrics"), params);

        int steps = 37;
        for (int i = 0; i < steps; i++) {
            engine.step();
        }

        assertEquals(steps, engine.getMetrics().getStepCount(),
                "stepCount deve avanzare esattamente di 1 per ogni chiamata a step()");
        assertEquals(steps * params.dt, engine.getMetrics().getSimulationTime(), 1e-6,
                "simulationTime deve avanzare esattamente di dt per ogni step");
    }

    // ---------------------------------------------------------------
    // Confini del sistema: caduta nella stella e fuga interstellare
    // ---------------------------------------------------------------

    @Test
    void step_removesParticle_thatFallsIntoTheStar(@TempDir Path tempDir) throws IOException {
        SimulationParams params = new SimulationParams();
        params.n = 2;

        // Particella ferma, gia' ben dentro il raggio stellare: qualunque spostamento indotto
        // dalla gravita' (che qui e' fortissima, essendo cosi' vicini) la lascia comunque dentro.
        Particle faller = particleAt(params.centralStarRadius * 0.3, 0, 0, 0, 0, 0, 1e15);
        // Particella "neutra" lontana, ininfluente, solo per non far girare l'engine con un singolo corpo.
        Particle bystander = particleAt(params.diskOuterRadius, 0, 0, 0, 0, 0, 1e15);

        List<Particle> particles = new ArrayList<>(List.of(faller, bystander));
        SimulationEngine engine = new SimulationEngine(particles, newLogger(tempDir, "starfall"), params);

        long starFallsBefore = engine.getMetrics().getTotalStarFalls();
        engine.step();

        assertEquals(starFallsBefore + 1, engine.getMetrics().getTotalStarFalls(),
                "Una particella ben dentro il raggio stellare deve essere registrata come caduta nella stella entro il primo step");
        assertEquals(1, engine.getParticles().size(),
                "La particella caduta nella stella deve sparire dalla lista delle particelle vive");
    }

    @Test
    void step_removesParticle_thatEscapesTheSystem(@TempDir Path tempDir) throws IOException {
        SimulationParams params = new SimulationParams();
        params.n = 2;

        double maxSystemRadius = params.diskOuterRadius * 3.0; // stesso limite usato da SimulationEngine.step()
        double farPosition = maxSystemRadius * 1.5;

        // Velocita' di fuga LOCALE a quella distanza, ampiamente superata: la particella e' gia'
        // oltre il bordo del sistema e si sta allontanando piu' veloce della velocita' di fuga.
        double vEscLocal = Math.sqrt(2.0 * PhysicsConstants.G * params.centralStarMass / farPosition);
        Particle escapee = particleAt(farPosition, 0, 0, vEscLocal * 3.0, 0, 0, 1e10);
        Particle bystander = particleAt(params.diskOuterRadius * 0.5, 0, 0, 0, 0, 0, 1e15);

        List<Particle> particles = new ArrayList<>(List.of(escapee, bystander));
        SimulationEngine engine = new SimulationEngine(particles, newLogger(tempDir, "escape"), params);

        long escapesBefore = engine.getMetrics().getTotalEscapes();
        engine.step();

        assertEquals(escapesBefore + 1, engine.getMetrics().getTotalEscapes(),
                "Una particella oltre il bordo del sistema e piu' veloce della velocita' di fuga locale deve essere registrata come fuga");
        assertEquals(1, engine.getParticles().size(),
                "La particella fuggita deve sparire dalla lista delle particelle vive");
    }

    @Test
    void step_doesNotRemove_particleWellWithinBounds_movingSlowly(@TempDir Path tempDir) throws IOException {
        // Controllo di sanita' complementare: una particella in un'orbita ordinaria (ne' troppo
        // vicina alla stella ne' oltre il bordo) non deve MAI essere rimossa per errore.
        SimulationParams params = new SimulationParams();
        params.n = 1;

        double r = PhysicsConstants.AU;
        double vCirc = Math.sqrt(PhysicsConstants.G * params.centralStarMass / r);
        Particle orbiting = particleAt(r, 0, 0, 0, vCirc, 0, 1e20);

        List<Particle> particles = new ArrayList<>(List.of(orbiting));
        SimulationEngine engine = new SimulationEngine(particles, newLogger(tempDir, "stable"), params);

        for (int i = 0; i < 50; i++) {
            engine.step();
        }

        assertEquals(1, engine.getParticles().size(),
                "Una particella in orbita ordinaria non deve mai essere rimossa per caduta o fuga");
        assertEquals(0, engine.getMetrics().getTotalStarFalls());
        assertEquals(0, engine.getMetrics().getTotalEscapes());
    }

    // ---------------------------------------------------------------
    // Pausa: step() non deve avanzare la simulazione quando l'engine e' in pausa (via run()/paused)
    // ---------------------------------------------------------------

    @Test
    void setPaused_true_doesNotPreventDirectStepCalls() {
        // Nota: setPaused/paused influenzano SOLO il ciclo run() (while(running){ if(paused) sleep; }),
        // non step() chiamato direttamente -- e' un comportamento INTENZIONALE (i test e gli strumenti
        // di debug possono avanzare la simulazione un passo alla volta anche a "pausa" logicamente
        // attiva), documentato qui esplicitamente per non essere scambiato in futuro per un bug.
        SimulationParams params = new SimulationParams();
        params.n = 1;
        Particle p = particleAt(PhysicsConstants.AU, 0, 0, 0, 0, 0, 1e20);

        SimulationEngine engine;
        try {
            engine = new SimulationEngine(new ArrayList<>(List.of(p)),
                    new RunLogger(java.nio.file.Files.createTempFile("pause-test", ".log")), params);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        engine.setPaused(true);
        assertTrue(engine.isPaused());

        long stepsBefore = engine.getMetrics().getStepCount();
        engine.step();
        assertEquals(stepsBefore + 1, engine.getMetrics().getStepCount(),
                "step() chiamato direttamente deve avanzare comunque, indipendentemente dal flag paused");
    }
}