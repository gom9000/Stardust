package net.gommagomma.stardust.benchmark;

import java.util.List;

import net.gommagomma.stardust.SimulationParams;
import net.gommagomma.stardust.TestParams;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;
import net.gommagomma.stardust.physics.Physics;
import net.gommagomma.stardust.physics.barneshut.BarnesHutTree;

/**
 * Trova i punti di incrocio REALI tra i tre algoritmi di calcolo forze (sequenziale O(N^2),
 * parallelo O(N^2) via IntStream.parallel(), Barnes-Hut O(N log N)) al variare di N -- per
 * verificare empiricamente le due soglie di dispatch che oggi vivono nel motore:
 *
 *   - n > 200 (sequenziale -> parallelo): oggi CABLATA in SimulationEngine.step(), non un
 *     parametro configurabile come barnesHutThreshold.
 *   - n >= params.barnesHutThreshold (parallelo -> Barnes-Hut): configurabile, default 400.
 *
 * Misura i tre algoritmi IN ISOLAMENTO (non attraverso il dispatch reale del motore), cosi' da
 * poterli confrontare a qualunque N, anche dove il dispatch oggi non li lascerebbe competere.
 *
 * ATTENZIONE: i risultati di questo benchmark dipendono FORTEMENTE dal numero di core reali
 * disponibili sulla macchina -- su una macchina con un solo core (o pochi core), il percorso
 * "parallelo" non puo' mostrare nessun vantaggio reale (anzi, ha solo l'overhead di
 * coordinamento tra thread in piu'), quindi il confronto sequenziale/parallelo va rifatto sulla
 * macchina di destinazione reale per essere significativo. Il confronto sequenziale/Barnes-Hut
 * è invece più stabile tra macchine diverse, perché la differenza è algoritmica (O(N^2) vs
 * O(N log N)), non di parallelismo.
 *
 * Non è un test JUnit: nessuna asserzione, solo una tabella da leggere.
 */
public class ForceDispatchThresholdBenchmark {

    private static final int[] NS = {
            50, 100, 150, 200, 250, 300, 400, 500, 700, 1000,
            1500, 2000, 3000, 5000, 8000, 12000, 15000
    };
    private static final int SEQUENTIAL_MAX_N = 3000; // oltre, troppo lento e gia' chiaramente perdente
    private static final long SEED = 123L;

    /** Ripetizioni ADATTIVE: a N piccola il lavoro dura sub-millisecondo, dove il rumore di
     *  sistema (GC, scheduling, compilazione JIT) domina completamente una misura con poche
     *  ripetizioni -- servono decine di ripetizioni per stabilizzare la media. A N grande il
     *  lavoro dura gia' abbastanza da rendere poche ripetizioni sufficienti, e servono poche per
     *  restare in un tempo ragionevole. */
    private static int repsFor(int n) {
        if (n <= 500) return 60;
        if (n <= 2000) return 20;
        return 5;
    }
    private static final int WARMUP_FRACTION_DIVISOR = 3; // un terzo delle ripetizioni come riscaldamento

    public static void main(String[] args) {
        SimulationParams params = TestParams.defaults();
        Physics physics = new Physics(params);

        System.out.printf("%-8s | %15s %15s %15s%n", "N", "sequenziale(ms)", "parallelo(ms)", "barnes-hut(ms)");
        System.out.println("-".repeat(65));

        for (int n : NS) {
            List<Particle> particles = BenchmarkFixtures.generateHeavyCloud(n, SEED);
            int reps = repsFor(n);
            int warmup = Math.max(1, reps / WARMUP_FRACTION_DIVISOR);

            double tSeq = (n <= SEQUENTIAL_MAX_N) ? timeSequential(particles, physics, warmup, reps) : Double.NaN;
            double tPar = timeParallel(particles, physics, warmup, reps);
            double tBH = timeBarnesHut(particles, params, physics, warmup, reps);

            if (Double.isNaN(tSeq)) {
                System.out.printf("%-8d | %15s %15.3f %15.3f  (reps=%d)%n", n, "(saltato)", tPar, tBH, reps);
            } else {
                System.out.printf("%-8d | %15.3f %15.3f %15.3f  (reps=%d)%n", n, tSeq, tPar, tBH, reps);
            }
        }

        System.out.println();
        System.out.println("Soglie attuali nel motore: sequenziale->parallelo a n>" + + params.parallelForcesThreshold + ", "
                + "parallelo->Barnes-Hut a n>=" + params.barnesHutThreshold + " (parametro barnesHutThreshold).");
        System.out.println("Core disponibili su questa macchina: " + Runtime.getRuntime().availableProcessors());
    }

    private static double timeSequential(List<Particle> particles, Physics physics, int warmup, int reps) {
        for (int i = 0; i < warmup; i++) computeSequential(particles, physics);

        long t0 = System.nanoTime();
        for (int i = 0; i < reps; i++) computeSequential(particles, physics);
        long t1 = System.nanoTime();

        return ((t1 - t0) / 1_000_000.0) / reps;
    }

    private static double timeParallel(List<Particle> particles, Physics physics, int warmup, int reps) {
        for (int i = 0; i < warmup; i++) computeParallel(particles, physics);

        long t0 = System.nanoTime();
        for (int i = 0; i < reps; i++) computeParallel(particles, physics);
        long t1 = System.nanoTime();

        return ((t1 - t0) / 1_000_000.0) / reps;
    }

    private static double timeBarnesHut(List<Particle> particles, SimulationParams params, Physics physics, int warmup, int reps) {
        for (int i = 0; i < warmup; i++) computeBarnesHut(particles, params, physics);

        long t0 = System.nanoTime();
        for (int i = 0; i < reps; i++) computeBarnesHut(particles, params, physics);
        long t1 = System.nanoTime();

        return ((t1 - t0) / 1_000_000.0) / reps;
    }

    /** Stessa logica di SimulationEngine.computeForcesSequential: somma diretta O(N^2), un solo thread.
     *  Le forze non vengono applicate alle particelle (evitiamo accumulo tra ripetizioni): la misura
     *  di tempo include comunque lo stesso lavoro reale (calcolo della forza per ogni coppia). */
    private static void computeSequential(List<Particle> particles, Physics physics) {
        int n = particles.size();
        double sinkX = 0, sinkY = 0, sinkZ = 0; // impedisce al JIT di eliminare il calcolo come "morto"
        for (int i = 0; i < n; i++) {
            Particle p1 = particles.get(i);
            for (int j = i + 1; j < n; j++) {
                Vector3D f = physics.calculateGravityAndElectrostaticForce(p1, particles.get(j));
                sinkX += f.getX();
                sinkY += f.getY();
                sinkZ += f.getZ();
            }
        }
        if (sinkX == Double.NaN && sinkY == Double.NaN && sinkZ == Double.NaN) {
            throw new AssertionError(); // mai vero, serve solo a impedire l'eliminazione del calcolo
        }
    }

    /** Stessa logica di SimulationEngine.computeForcesParallel: somma diretta O(N^2) via IntStream.parallel().
     *  NOTA: itera TUTTE le coppie (i,j) comprese quelle ripetute in ordine inverso (j,i), a differenza
     *  di computeSequential che sfrutta la terza legge di Newton (i<j) -- il doppio del lavoro grezzo,
     *  indipendentemente da quanti core sono disponibili. */
    private static void computeParallel(List<Particle> particles, Physics physics) {
        int n = particles.size();
        java.util.concurrent.atomic.DoubleAdder sink = new java.util.concurrent.atomic.DoubleAdder();
        java.util.stream.IntStream.range(0, n).parallel().forEach(i -> {
            Particle p1 = particles.get(i);
            double fx = 0, fy = 0, fz = 0;
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                Vector3D f = physics.calculateGravityAndElectrostaticForce(p1, particles.get(j));
                fx += f.getX();
                fy += f.getY();
                fz += f.getZ();
            }
            sink.add(fx + fy + fz);
        });
        if (Double.isNaN(sink.sum())) throw new AssertionError(); // mai vero, impedisce l'eliminazione del calcolo
    }

    /** Stessa logica di SimulationEngine.computeForcesBarnesHut: costruzione albero + calcolo per ogni particella. */
    private static void computeBarnesHut(List<Particle> particles, SimulationParams params, Physics physics) {
        BarnesHutTree tree = new BarnesHutTree(particles, params, physics);
        int n = particles.size();
        java.util.concurrent.atomic.DoubleAdder sink = new java.util.concurrent.atomic.DoubleAdder();
        java.util.stream.IntStream.range(0, n).parallel().forEach(i -> {
            Vector3D f = tree.computeForce(particles.get(i));
            sink.add(f.getX() + f.getY() + f.getZ());
        });
        if (Double.isNaN(sink.sum())) throw new AssertionError();
    }
}