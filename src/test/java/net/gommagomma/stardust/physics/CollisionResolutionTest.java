package net.gommagomma.stardust.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;
import net.gommagomma.stardust.physics.collision.CollisionResult;

/**
 * Verifica le leggi di conservazione (massa, quantità di moto) nella risoluzione delle collisioni:
 * fusione (mergeParticles), rimbalzo (resolveBounce) e frammentazione (fragmentParticles).
 *
 * Nota: queste routine non dipendono da G, quindi si usano masse/distanze "di laboratorio"
 * (kg, m, m/s) per rendere i numeri leggibili; le leggi di conservazione testate sono le stesse
 * a qualunque scala.
 */
class CollisionResolutionTest {

    private static final double TOL = 1e-9;

    private static Vector3D momentum(Particle p) {
        return p.getVelocity().multiply(p.getMass());
    }

    // ---------------------------------------------------------------
    // evaluateCollision: instradamento verso MERGE / BOUNCE / FRAGMENT
    // ---------------------------------------------------------------

    @Test
    void evaluateCollision_returnsMerge_whenRelativeSpeedIsLow() {
        // Velocità relativa nulla: ben sotto qualunque soglia di fuga/frammentazione -> deve fondersi.
        Particle p1 = new Particle(new Vector3D(0, 0, 0), new Vector3D(0, 0, 0), 1e15, 0.0, 3000.0);
        Particle p2 = new Particle(new Vector3D(10, 0, 0), new Vector3D(0, 0, 0), 1e15, 0.0, 3000.0);

        assertEquals(CollisionResult.MERGE, Physics.evaluateCollision(p1, p2));
    }

    @Test
    void evaluateCollision_returnsFragment_whenRelativeSpeedIsVeryHigh() {
        // Velocità relativa enorme rispetto alla velocità di fuga -> deve superare anche la soglia di frammentazione.
        Particle p1 = new Particle(new Vector3D(0, 0, 0), new Vector3D(100000, 0, 0), 1e15, 0.0, 3000.0);
        Particle p2 = new Particle(new Vector3D(10, 0, 0), new Vector3D(-100000, 0, 0), 1e15, 0.0, 3000.0);

        assertEquals(CollisionResult.FRAGMENT, Physics.evaluateCollision(p1, p2));
    }

    // ---------------------------------------------------------------
    // mergeParticles
    // ---------------------------------------------------------------

    @Test
    void mergeParticles_conservesMassAndMomentum() {
        Particle winner = new Particle(new Vector3D(0, 0, 0), new Vector3D(3, 0, 0), 5.0, 0.0, 3000.0);
        Particle loser = new Particle(new Vector3D(2, 0, 0), new Vector3D(-1, 4, 0), 2.0, 0.0, 3000.0);

        double totalMassBefore = winner.getMass() + loser.getMass();
        Vector3D totalMomentumBefore = momentum(winner).add(momentum(loser));

        Physics.mergeParticles(winner, loser);

        assertEquals(totalMassBefore, winner.getMass(), TOL, "La massa totale deve conservarsi nella fusione");
        assertFalse(loser.isAlive(), "Il corpo 'perdente' deve essere marcato come non più vivo");
        assertTrue(winner.isAlive(), "Il corpo 'vincitore' deve restare vivo");

        Vector3D momentumAfter = momentum(winner); // loser non contribuisce più (massa "assorbita" in winner)
        assertEquals(totalMomentumBefore.getX(), momentumAfter.getX(), Math.abs(totalMomentumBefore.getX()) * 1e-6 + TOL);
        assertEquals(totalMomentumBefore.getY(), momentumAfter.getY(), Math.abs(totalMomentumBefore.getY()) * 1e-6 + TOL);
        assertEquals(totalMomentumBefore.getZ(), momentumAfter.getZ(), Math.abs(totalMomentumBefore.getZ()) * 1e-6 + TOL);
    }

    @Test
    void mergeParticles_positionIsMassWeightedAverage() {
        Particle winner = new Particle(new Vector3D(0, 0, 0), new Vector3D(0, 0, 0), 3.0, 0.0, 3000.0);
        Particle loser = new Particle(new Vector3D(10, 0, 0), new Vector3D(0, 0, 0), 1.0, 0.0, 3000.0);

        Physics.mergeParticles(winner, loser);

        // Baricentro atteso: (3*0 + 1*10) / 4 = 2.5
        assertEquals(2.5, winner.getPosition().getX(), TOL);
    }

    @Test
    void mergeParticles_radiusGrowsAfterAbsorbingMass() {
        Particle winner = new Particle(new Vector3D(0, 0, 0), new Vector3D(0, 0, 0), 1.0, 0.0, 3000.0);
        Particle loser = new Particle(new Vector3D(5, 0, 0), new Vector3D(0, 0, 0), 1.0, 0.0, 3000.0);
        double radiusBefore = winner.getRadius();

        Physics.mergeParticles(winner, loser);

        assertTrue(winner.getRadius() > radiusBefore,
                "Assorbendo massa, il raggio del corpo risultante deve aumentare rispetto al raggio originale del vincitore");
    }

    // ---------------------------------------------------------------
    // resolveBounce
    // ---------------------------------------------------------------

    @Test
    void resolveBounce_conservesMomentum_regardlessOfRestitution() {
        for (double restitution : new double[] {0.0, 0.5, 1.0}) {
            Particle p1 = new Particle(new Vector3D(0, 0, 0), new Vector3D(5, 0, 0), 2.0, 0.0, 3000.0);
            Particle p2 = new Particle(new Vector3D(1, 0, 0), new Vector3D(-5, 0, 0), 1.0, 0.0, 3000.0);

            Vector3D momentumBefore = momentum(p1).add(momentum(p2));
            Physics.resolveBounce(p1, p2, restitution);
            Vector3D momentumAfter = momentum(p1).add(momentum(p2));

            assertEquals(momentumBefore.getX(), momentumAfter.getX(), 1e-6,
                    "Quantità di moto totale non conservata per restitution=" + restitution);
        }
    }

    @Test
    void resolveBounce_elasticCase_conservesKineticEnergyAlongNormal() {
        // Urto 1D perfettamente elastico (restitution = 1): l'energia cinetica totale deve conservarsi.
        Particle p1 = new Particle(new Vector3D(0, 0, 0), new Vector3D(5, 0, 0), 2.0, 0.0, 3000.0);
        Particle p2 = new Particle(new Vector3D(1, 0, 0), new Vector3D(-3, 0, 0), 1.0, 0.0, 3000.0);

        double keBefore = 0.5 * p1.getMass() * p1.getVelocity().magnitudeSquared()
                + 0.5 * p2.getMass() * p2.getVelocity().magnitudeSquared();

        Physics.resolveBounce(p1, p2, 1.0);

        double keAfter = 0.5 * p1.getMass() * p1.getVelocity().magnitudeSquared()
                + 0.5 * p2.getMass() * p2.getVelocity().magnitudeSquared();

        assertEquals(keBefore, keAfter, keBefore * 1e-6, "Un urto con restitution=1.0 deve conservare l'energia cinetica");
    }

    @Test
    void resolveBounce_perfectlyInelasticCase_stopsRelativeMotionAlongNormal() {
        // Con restitution = 0, dopo l'urto le due particelle non devono più avvicinarsi lungo la normale
        // (velocità relativa lungo la normale ~ 0), pur senza fondersi (restano due corpi distinti).
        Particle p1 = new Particle(new Vector3D(0, 0, 0), new Vector3D(5, 0, 0), 2.0, 0.0, 3000.0);
        Particle p2 = new Particle(new Vector3D(1, 0, 0), new Vector3D(-5, 0, 0), 1.0, 0.0, 3000.0);

        Physics.resolveBounce(p1, p2, 0.0);

        Vector3D normal = p1.getPosition().subtract(p2.getPosition()).normalize();
        double velAlongNormalAfter = p1.getVelocity().subtract(p2.getVelocity()).dotProduct(normal);

        assertEquals(0.0, velAlongNormalAfter, 1e-6,
                "Con restitution=0 la componente della velocità relativa lungo la normale deve annullarsi");
    }

    @Test
    void resolveBounce_noImpulse_whenParticlesAlreadySeparating() {
        // Se le due particelle si stanno già allontanando lungo la normale, non deve essere applicato
        // alcun impulso (le velocità non devono cambiare).
        Particle p1 = new Particle(new Vector3D(0, 0, 0), new Vector3D(-5, 0, 0), 2.0, 0.0, 3000.0);
        Particle p2 = new Particle(new Vector3D(1, 0, 0), new Vector3D(5, 0, 0), 1.0, 0.0, 3000.0);

        Vector3D v1Before = p1.getVelocity();
        Vector3D v2Before = p2.getVelocity();

        Physics.resolveBounce(p1, p2, 0.8);

        assertEquals(v1Before.getX(), p1.getVelocity().getX(), TOL);
        assertEquals(v2Before.getX(), p2.getVelocity().getX(), TOL);
    }

    // ---------------------------------------------------------------
    // fragmentParticles
    // ---------------------------------------------------------------

    @Test
    void fragmentParticles_conservesMassAndMomentum_andKillsOriginals() {
        Particle p1 = new Particle(new Vector3D(0, 0, 0), new Vector3D(50, 0, 0), 5.0, 0.0, 3000.0);
        Particle p2 = new Particle(new Vector3D(1, 0, 0), new Vector3D(-30, 10, 0), 3.0, 0.0, 3000.0);

        double totalMassBefore = p1.getMass() + p2.getMass();
        Vector3D totalMomentumBefore = momentum(p1).add(momentum(p2));

        List<Particle> fragments = Physics.fragmentParticles(p1, p2);

        assertFalse(p1.isAlive(), "Il corpo originale p1 deve essere marcato come distrutto");
        assertFalse(p2.isAlive(), "Il corpo originale p2 deve essere marcato come distrutto");
        assertTrue(fragments.size() >= 2 && fragments.size() <= 5,
                "Il numero di frammenti generati deve essere tra 2 e 5 (per costruzione: 2 + random*4)");

        double totalMassAfter = fragments.stream().mapToDouble(Particle::getMass).sum();
        assertEquals(totalMassBefore, totalMassAfter, totalMassBefore * 1e-9,
                "La massa totale deve conservarsi nella frammentazione");

        Vector3D totalMomentumAfter = fragments.stream()
                .map(CollisionResolutionTest::momentum)
                .reduce(new Vector3D(0, 0, 0), Vector3D::add);

        double momentumScale = totalMomentumBefore.magnitude();
        double tol = Math.max(momentumScale * 1e-6, 1e-9);
        assertEquals(totalMomentumBefore.getX(), totalMomentumAfter.getX(), tol,
                "La quantità di moto totale (asse x) deve conservarsi nella frammentazione");
        assertEquals(totalMomentumBefore.getY(), totalMomentumAfter.getY(), tol,
                "La quantità di moto totale (asse y) deve conservarsi nella frammentazione");
        assertEquals(totalMomentumBefore.getZ(), totalMomentumAfter.getZ(), tol,
                "La quantità di moto totale (asse z) deve conservarsi nella frammentazione");
    }

    @Test
    void fragmentParticles_allFragmentsHavePositiveMass() {
        Particle p1 = new Particle(new Vector3D(0, 0, 0), new Vector3D(50, 0, 0), 5.0, 0.0, 3000.0);
        Particle p2 = new Particle(new Vector3D(1, 0, 0), new Vector3D(-30, 0, 0), 3.0, 0.0, 3000.0);

        List<Particle> fragments = Physics.fragmentParticles(p1, p2);

        for (Particle frag : fragments) {
            assertTrue(frag.getMass() > 0, "Ogni frammento deve avere massa strettamente positiva");
            assertTrue(frag.isAlive(), "Ogni frammento generato deve essere vivo");
        }
    }
}
