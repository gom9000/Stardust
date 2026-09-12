package net.gommagomma.stardust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Verifica CourantMonitor su due fronti distinti:
 * 1. Correttezza base a thread singolo: il massimo registrato e la coppia di ID che lo ha
 *    generato devono sempre corrispondere esattamente, anche quando si susseguono candidati che
 *    non superano il massimo corrente (non devono "vincere" per errore).
 * 2. Correttezza sotto concorrenza REALE (non simulata): molti thread aggiornano lo stesso monitor
 *    contemporaneamente con candidati generati a caso; il valore finale e la coppia di ID devono
 *    corrispondere esattamente a quali che siano stati generati insieme dallo stesso thread,
 *    mai una combinazione "spuria" di valore di un candidato e ID di un altro.
 */
class CourantMonitorTest {

    @Test
    void update_recordsMax_withMatchingPairId_singleThreaded() {
        CourantMonitor monitor = new CourantMonitor();

        monitor.update(1.0, 10, 20);
        assertEquals(1.0, monitor.getMax(), 1e-12);
        assertEquals(10, monitor.getMaxPairId1());
        assertEquals(20, monitor.getMaxPairId2());

        // Un candidato PIU' BASSO non deve sostituire ne' il valore ne' la coppia
        monitor.update(0.5, 99, 98);
        assertEquals(1.0, monitor.getMax(), 1e-12);
        assertEquals(10, monitor.getMaxPairId1());
        assertEquals(20, monitor.getMaxPairId2());

        // Un candidato PIU' ALTO deve sostituire entrambi insieme
        monitor.update(5.0, 30, 40);
        assertEquals(5.0, monitor.getMax(), 1e-12);
        assertEquals(30, monitor.getMaxPairId1());
        assertEquals(40, monitor.getMaxPairId2());
    }

    @Test
    void update_equalCandidate_doesNotReplace_existingPair() {
        // Un candidato uguale (non strettamente maggiore) non deve rimpiazzare la coppia registrata
        // -- altrimenti, a parita' di valore, l'ultima coppia esaminata "vincerebbe" arbitrariamente.
        CourantMonitor monitor = new CourantMonitor();
        monitor.update(2.0, 1, 2);
        monitor.update(2.0, 3, 4);

        assertEquals(2.0, monitor.getMax(), 1e-12);
        assertEquals(1, monitor.getMaxPairId1());
        assertEquals(2, monitor.getMaxPairId2());
    }

    @Test
    void reset_clearsBothValueAndPair() {
        CourantMonitor monitor = new CourantMonitor();
        monitor.update(3.0, 7, 8);
        monitor.reset();

        assertEquals(0.0, monitor.getMax(), 1e-12);
        assertEquals(-1, monitor.getMaxPairId1());
        assertEquals(-1, monitor.getMaxPairId2());
    }

    @Test
    void nominalDt_isStoredAndRetrievedIndependently() {
        // Il dt nominale e' un campo distinto dal Courant, letto/scritto solo dal thread
        // orchestratore -- verifichiamo semplicemente che vada e torni invariato.
        CourantMonitor monitor = new CourantMonitor();
        monitor.setNominalDt(1200.0);
        assertEquals(1200.0, monitor.getNominalDt(), 1e-12);
    }

    @Test
    void update_underRealConcurrency_neverMismatchesValueAndPair() throws Exception {
        // Molti thread reali (non un solo thread che simula concorrenza) aggiornano lo stesso
        // monitor con candidati (valore, id1, id2) generati insieme e MAI più riutilizzati altrove
        // -- cosi' se il valore finale registrato non corrisponde ESATTAMENTE alla coppia generata
        // insieme ad esso, lo sappiamo con certezza (non e' un candidato "simile" per caso).
        CourantMonitor monitor = new CourantMonitor();

        int threadCount = 16;
        int candidatesPerThread = 20_000;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // Registro di tutti i candidati (valore -> coppia di ID) effettivamente generati, per poter
        // verificare a posteriori che il massimo finale sia uno di questi, con la coppia corretta.
        java.util.concurrent.ConcurrentHashMap<Double, int[]> generated = new java.util.concurrent.ConcurrentHashMap<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    Random rnd = new Random(threadId * 7919L + 1);
                    for (int i = 0; i < candidatesPerThread; i++) {
                        // Valore unico per candidato (thread*grande_costante + indice), cosi' non
                        // ci sono mai due candidati con lo stesso valore generati da coppie diverse
                        // -- altrimenti il test non potrebbe distinguere un vero disallineamento
                        // da una legittima parita' di valore.
                        double candidate = threadId * 1_000_000.0 + i + rnd.nextDouble();
                        int id1 = threadId * 100_000 + i;
                        int id2 = -(threadId * 100_000 + i) - 1; // negativo, per distinguerlo facilmente da id1
                        generated.put(candidate, new int[]{id1, id2});
                        monitor.update(candidate, id1, id2);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "i thread di test non hanno finito in tempo");
        pool.shutdown();

        double finalMax = monitor.getMax();
        int finalId1 = monitor.getMaxPairId1();
        int finalId2 = monitor.getMaxPairId2();

        assertTrue(generated.containsKey(finalMax),
                "il valore massimo finale (" + finalMax + ") non corrisponde a NESSUN candidato realmente generato");

        int[] expectedPair = generated.get(finalMax);
        assertEquals(expectedPair[0], finalId1,
                "il primo ID della coppia non corrisponde al candidato che ha generato questo valore massimo");
        assertEquals(expectedPair[1], finalId2,
                "il secondo ID della coppia non corrisponde al candidato che ha generato questo valore massimo");
    }
}
