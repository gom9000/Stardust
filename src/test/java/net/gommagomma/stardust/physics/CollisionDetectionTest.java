package net.gommagomma.stardust.physics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.gommagomma.stardust.SimulationConfig;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;

/**
 * Verifica Physics.checkCollision, la Continuous Collision Detection (CCD) che controlla
 * il segmento REALMENTE percorso nello step (previousPosition -> position), non solo la
 * distanza istantanea a fine step.
 *
 * Il caso critico è il "tunneling": due corpi che si incrociano DENTRO lo step ma che,
 * a inizio e fine step, si trovano entrambi a distanza di sicurezza. Un controllo basato
 * solo sulla posizione finale li dichiarerebbe (erroneamente) non in collisione.
 */
class CollisionDetectionTest {

    // Massa/densità scelte per ottenere un raggio di captura piccolo e leggibile (qualche metro),
    // così le distanze di test (centinaia/migliaia di metri) sono chiaramente "grandi" al confronto.
    private static Particle particleAt(double x, double vx) {
        double mass = 1.0e6;   // kg
        double density = 3000.0; // kg/m^3 -> raggio ~4.3 m
        return new Particle(new Vector3D(x, 0, 0), new Vector3D(vx, 0, 0), mass, 0.0, density);
    }

    /** Fa avanzare la particella di un intero step con forza esterna nulla (moto rettilineo uniforme),
     *  cosi' previousPosition/position restano coerenti con l'ipotesi usata da checkCollision. */
    private static void advanceFreely(Particle p, double dt) {
        p.resetForce();
        p.update(dt);
    }

    @Test
    void checkCollision_true_whenAlreadyOverlappingAtStartOfStep() {
        Particle p1 = particleAt(0.0, 0.0);
        Particle p2 = particleAt(1.0, 0.0); // 1 m di distanza, ben sotto il raggio combinato (~8.6 m)

        assertTrue(Physics.checkCollision(p1, p2),
                "Se le particelle sono già sovrapposte a inizio step, deve rilevare collisione anche a velocità relativa nulla");
    }

    @Test
    void checkCollision_false_whenParticlesNeverGetClose() {
        Particle p1 = particleAt(-1000.0, 1.0);
        Particle p2 = particleAt(1000.0, -1.0);

        // Step lento: in un dt della simulazione si avvicinano di poco, restando molto distanti.
        advanceFreely(p1, SimulationConfig.DT);
        advanceFreely(p2, SimulationConfig.DT);

        assertFalse(Physics.checkCollision(p1, p2),
                "Due particelle che restano a migliaia di metri di distanza per tutto lo step non devono collidere");
    }

    @Test
    void checkCollision_detectsTunneling_evenWhenBothEndpointsAreFar() {
        double dt = SimulationConfig.DT;

        // Impostati per incontrarsi ESATTAMENTE a metà del segmento (t = 100s, dentro [0, dt=300s]),
        // ma a inizio (distanza 2000 m) e fine step (distanza 4000 m) le particelle sono ben separate:
        // un controllo naive sulla sola posizione finale (o iniziale) le dichiarerebbe non in collisione.
        Particle p1 = particleAt(-1000.0, 10.0);
        Particle p2 = particleAt(1000.0, -10.0);

        double initialDistance = p1.getPosition().distanceTo(p2.getPosition());
        advanceFreely(p1, dt);
        advanceFreely(p2, dt);
        double finalDistance = p1.getPosition().distanceTo(p2.getPosition());

        assertTrue(initialDistance > 100, "Precondizione test: a inizio step devono essere lontane");
        assertTrue(finalDistance > 100, "Precondizione test: a fine step devono essere lontane (si sono superate)");

        assertTrue(Physics.checkCollision(p1, p2),
                "La CCD deve rilevare l'incrocio avvenuto durante lo step anche se inizio e fine step sono entrambi 'sicuri' (tunneling)");
    }

    @Test
    void checkCollision_false_whenClosestApproachOutsideCaptureRadius() {
        double dt = SimulationConfig.DT;

        // Stessa geometria del test di tunneling ma con velocità molto più basse: la minima distanza
        // raggiunta entro lo step resta ben oltre il raggio di cattura combinato.
        Particle p1 = particleAt(-1000.0, 1.0);
        Particle p2 = particleAt(1000.0, -1.0);

        advanceFreely(p1, dt);
        advanceFreely(p2, dt);

        assertFalse(Physics.checkCollision(p1, p2),
                "Se la distanza minima raggiunta nello step resta oltre il raggio di cattura combinato, non deve esserci collisione");
    }

    @Test
    void checkCollision_true_whenClosestApproachIsExactlyAtCombinedCaptureRadius() {
        // Caso limite: minima distanza == raggio di cattura combinato -> deve valere "<=", quindi collisione.
        Particle p1 = particleAt(0.0, 0.0);
        double combinedRadius = Physics.getEffectiveCaptureRadius(p1) + Physics.getEffectiveCaptureRadius(p1);
        Particle p2 = particleAt(combinedRadius, 0.0);

        assertTrue(Physics.checkCollision(p1, p2),
                "Alla distanza esattamente uguale al raggio di cattura combinato, il confronto <= deve dare collisione");
    }
}
