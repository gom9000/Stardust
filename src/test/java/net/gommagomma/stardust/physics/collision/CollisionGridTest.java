package net.gommagomma.stardust.physics.collision;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;

/**
 * Verifica CollisionGrid, la fase "broad phase" che riduce la ricerca dei contatti da O(N^2)
 * a vicini prossimi. La proprietà critica per la correttezza fisica NON è la precisione (la
 * griglia può ragionevolmente restituire falsi positivi, cioè vicini un po' più lontani del
 * raggio richiesto, che verranno poi scartati dal controllo di collisione vero e proprio), ma
 * l'assenza di FALSI NEGATIVI: qualunque particella entro il raggio di query deve comparire
 * nel risultato, altrimenti una collisione reale verrebbe persa a monte.
 */
class CollisionGridTest {

    private static Particle particleAt(double x, double y, double z) {
        return new Particle(new Vector3D(x, y, z), new Vector3D(0, 0, 0), 1.0, 0.0, 3000.0);
    }

    @Test
    void queryNeighbors_hasNoFalseNegatives_forParticlesWithinRadius() {
        double cellSize = 10.0;
        CollisionGrid grid = new CollisionGrid(cellSize);

        // Particelle sparse su un'area più ampia della singola cella, per forzare la ricerca
        // a dover attraversare più bucket adiacenti.
        List<Particle> all = new ArrayList<>();
        for (int i = -3; i <= 3; i++) {
            for (int j = -3; j <= 3; j++) {
                Particle p = particleAt(i * 4.0, j * 4.0, 0.0);
                all.add(p);
                grid.insert(p);
            }
        }

        Vector3D queryCenter = new Vector3D(0, 0, 0);
        double radius = 9.0;

        List<Particle> result = new ArrayList<>();
        grid.queryNeighbors(queryCenter, radius, result);

        // Riferimento: tutte le particelle effettivamente entro 'radius' dal centro, calcolate per forza bruta.
        List<Particle> expectedWithinRadius = new ArrayList<>();
        for (Particle p : all) {
            if (p.getPosition().distanceTo(queryCenter) <= radius) {
                expectedWithinRadius.add(p);
            }
        }

        assertTrue(expectedWithinRadius.size() > 0, "Precondizione test: ci devono essere particelle entro il raggio");

        for (Particle p : expectedWithinRadius) {
            assertTrue(result.contains(p),
                    "La particella in posizione " + p.getPosition() + " è entro il raggio di query ma NON è stata restituita da queryNeighbors (falso negativo)");
        }
    }

    @Test
    void clear_removesAllPreviouslyInsertedParticles() {
        CollisionGrid grid = new CollisionGrid(10.0);
        Particle p = particleAt(0, 0, 0);
        grid.insert(p);

        List<Particle> resultBefore = new ArrayList<>();
        grid.queryNeighbors(new Vector3D(0, 0, 0), 5.0, resultBefore);
        assertTrue(resultBefore.contains(p), "Precondizione: la particella deve essere trovabile prima del clear()");

        grid.clear();

        List<Particle> resultAfter = new ArrayList<>();
        grid.queryNeighbors(new Vector3D(0, 0, 0), 5.0, resultAfter);
        assertTrue(resultAfter.isEmpty(),
                "Dopo clear() la griglia non deve restituire più alcuna particella dello step precedente");
    }

    @Test
    void wellSeparatedParticles_areNotReturned_forSmallQueryRadius() {
        CollisionGrid grid = new CollisionGrid(10.0);
        Particle near = particleAt(0, 0, 0);
        Particle far = particleAt(1000, 1000, 1000);
        grid.insert(near);
        grid.insert(far);

        List<Particle> result = new ArrayList<>();
        grid.queryNeighbors(new Vector3D(0, 0, 0), 5.0, result);

        assertTrue(result.contains(near), "La particella vicina deve comparire nel risultato");
        assertFalse(result.contains(far), "Una particella a 1000 unità di distanza non deve comparire per un raggio di query di 5 unità");
    }

    @Test
    void insert_ignoresDeadParticles() {
        CollisionGrid grid = new CollisionGrid(10.0);
        Particle dead = particleAt(0, 0, 0);
        dead.setAlive(false);
        grid.insert(dead);

        List<Particle> result = new ArrayList<>();
        grid.queryNeighbors(new Vector3D(0, 0, 0), 5.0, result);

        assertFalse(result.contains(dead), "Una particella non più viva non deve essere restituita dalla griglia");
    }

    @Test
    void insert_and_query_handleLargeCoordinates_withoutCrashing() {
        // Le coordinate vengono impacchettate in indici di cella a 21 bit con clamping (vedi commento
        // nel codice sorgente): verifichiamo solo che l'uso con coordinate molto grandi (oltre l'intervallo
        // rappresentabile) non generi eccezioni, dato che è un caso limite esplicitamente gestito col clamp.
        CollisionGrid grid = new CollisionGrid(10.0);
        Particle extreme = particleAt(1e20, -1e20, 1e20);

        grid.insert(extreme);
        List<Particle> result = new ArrayList<>();
        grid.queryNeighbors(new Vector3D(1e20, -1e20, 1e20), 5.0, result);
        // Non asseriamo il contenuto esatto (il clamping può aliasare celle lontanissime): l'obiettivo
        // di questo test è solo di regressione contro crash/eccezioni su input estremi.
    }
}
