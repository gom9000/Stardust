package net.gommagomma.stardust.physics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.gommagomma.stardust.PhysicsConstants;
import net.gommagomma.stardust.SimulationParams;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;

/**
 * Estende il test di orbita a due corpi a un vero sistema N-corpi, applicando ad ogni step
 * lo stesso schema di accumulo a coppie usato da SimulationEngine.computeForcesSequential.
 */
class MultiBodyOrbitIntegrationTest {

    private final SimulationParams params = new SimulationParams();
    private final Physics physics = new Physics(params);

    private double starPotential(Particle p) {
        return physics.calculateCentralStarPotentialEnergy(p);
    }

    private static double mutualPotential(Particle p1, Particle p2) {
        double dist = p1.getPosition().distanceTo(p2.getPosition());
        if (dist <= 0) return 0.0;
        return -(PhysicsConstants.G * p1.getMass() * p2.getMass()) / dist;
    }

    private static double kinetic(Particle p) {
        double v = p.getVelocity().magnitude();
        return 0.5 * p.getMass() * v * v;
    }

    private double totalSystemEnergy(List<Particle> planetesimals) {
        double energy = 0.0;
        for (Particle p : planetesimals) {
            energy += kinetic(p) + starPotential(p);
        }
        for (int i = 0; i < planetesimals.size(); i++) {
            for (int j = i + 1; j < planetesimals.size(); j++) {
                energy += mutualPotential(planetesimals.get(i), planetesimals.get(j));
            }
        }
        return energy;
    }

    /** Riproduce lo schema reale dell'engine: gravità stella (a coppie singole) + gravità mutua (a coppie N-N). */
    private void stepSystem(List<Particle> planetesimals, double dt) {
        for (Particle p : planetesimals) {
            p.resetForce();
            p.addForce(physics.calculateCentralStarGravity(p));
        }
        for (int i = 0; i < planetesimals.size(); i++) {
            Particle p1 = planetesimals.get(i);
            for (int j = i + 1; j < planetesimals.size(); j++) {
                Particle p2 = planetesimals.get(j);
                Vector3D f = physics.calculateGravity(p1, p2);
                p1.addForce(f);
                p2.addForce(f.multiply(-1));
            }
        }
        for (Particle p : planetesimals) {
            p.update(dt);
        }
    }

    @Test
    void twoIndependentOrbits_conserveTotalSystemEnergy_overOneShortPeriod() {
        double r1 = PhysicsConstants.AU;
        double r2 = 1.6 * PhysicsConstants.AU;

        double v1 = Math.sqrt((PhysicsConstants.G * params.centralStarMass) / r1);
        double v2 = Math.sqrt((PhysicsConstants.G * params.centralStarMass) / r2);

        Particle planetA = new Particle(new Vector3D(r1, 0, 0), new Vector3D(0, v1, 0), 1.0e20, 0.0, 3000.0);
        Particle planetB = new Particle(new Vector3D(0, r2, 0), new Vector3D(-v2, 0, 0), 1.0e20, 0.0, 3000.0);

        List<Particle> system = List.of(planetA, planetB);

        double period1 = 2.0 * Math.PI * Math.sqrt(Math.pow(r1, 3) / (PhysicsConstants.G * params.centralStarMass));
        double dt = params.dt;
        int steps = (int) Math.round(period1 / dt);

        double energyInitial = totalSystemEnergy(system);
        double maxDrift = 0.0;

        for (int i = 0; i < steps; i++) {
            stepSystem(system, dt);
            double drift = Math.abs((totalSystemEnergy(system) - energyInitial) / energyInitial);
            maxDrift = Math.max(maxDrift, drift);
        }

        assertTrue(maxDrift < 0.01,
                "L'energia totale del sistema (2 planetesimi + stella, con gravità mutua inclusa) non deve derivare più dell'1% "
                        + "durante un periodo orbitale del corpo interno (drift osservato: " + (maxDrift * 100) + "%)");
    }

    @Test
    void mutualAttraction_pullsTwoCoOrbitingBodiesTogether_whenStarGravityIsAbsent() {
        Particle p1 = new Particle(new Vector3D(-5e6, 0, 0), new Vector3D(0, 0, 0), 1.0e23, 0.0, 3000.0);
        Particle p2 = new Particle(new Vector3D(5e6, 0, 0), new Vector3D(0, 0, 0), 1.0e23, 0.0, 3000.0);

        double initialSeparation = p1.getPosition().distanceTo(p2.getPosition());

        double dt = 10.0;
        for (int i = 0; i < 200; i++) {
            p1.resetForce();
            p2.resetForce();
            Vector3D f = physics.calculateGravity(p1, p2);
            p1.addForce(f);
            p2.addForce(f.multiply(-1));
            p1.update(dt);
            p2.update(dt);
        }

        double finalSeparation = p1.getPosition().distanceTo(p2.getPosition());
        assertTrue(finalSeparation < initialSeparation,
                "Due corpi soggetti solo alla reciproca gravità devono avvicinarsi, non allontanarsi "
                        + "(separazione iniziale=" + initialSeparation + ", finale=" + finalSeparation + ")");
    }

    @Test
    void hierarchicalSystem_keepsSatelliteBoundToPlanet_overFullOrbitalPeriods() {
        double starMass = 1.989e30;
        double planetMass = 5.972e24;
        double satelliteMass = 7.342e22;

        double starPlanetDist = 1.5e11;
        double planetMoonDist = 4.0e8;

        Particle star = new Particle(new Vector3D(0, 0, 0), new Vector3D(0, 0, 0), starMass, 0.0, 1408.0);

        double vPlanet = Math.sqrt(PhysicsConstants.G * starMass / starPlanetDist);
        Particle planet = new Particle(
                new Vector3D(starPlanetDist, 0, 0),
                new Vector3D(0, vPlanet, 0),
                planetMass, 0.0, 5500.0
        );

        double vMoonRel = Math.sqrt(PhysicsConstants.G * planetMass / planetMoonDist);
        Particle satellite = new Particle(
                new Vector3D(starPlanetDist + planetMoonDist, 0, 0),
                new Vector3D(0, vPlanet + vMoonRel, 0),
                satelliteMass, 0.0, 3340.0
        );

        List<Particle> system = List.of(star, planet, satellite);
        double initialMoonDist = planet.getPosition().distanceTo(satellite.getPosition());

        double moonPeriod = 2.0 * Math.PI * Math.sqrt(Math.pow(planetMoonDist, 3) / (PhysicsConstants.G * planetMass));
        double dt = 300.0;
        int steps = (int) Math.round(1000.0 * moonPeriod / dt);

        double minMoonDist = Double.MAX_VALUE;
        double maxMoonDist = 0.0;

        for (int i = 0; i < steps; i++) {
            for (Particle p : system) {
                p.resetForce();
            }

            Vector3D fStarPlanet = physics.calculateGravity(star, planet);
            star.addForce(fStarPlanet);
            planet.addForce(fStarPlanet.multiply(-1));

            Vector3D fStarSat = physics.calculateGravity(star, satellite);
            star.addForce(fStarSat);
            satellite.addForce(fStarSat.multiply(-1));

            Vector3D fPlanetSat = physics.calculateGravity(planet, satellite);
            planet.addForce(fPlanetSat);
            satellite.addForce(fPlanetSat.multiply(-1));

            for (Particle p : system) {
                p.update(dt);
            }

            double d = planet.getPosition().distanceTo(satellite.getPosition());
            minMoonDist = Math.min(minMoonDist, d);
            maxMoonDist = Math.max(maxMoonDist, d);
        }

        double finalMoonDist = planet.getPosition().distanceTo(satellite.getPosition());

        assertTrue(minMoonDist > 0.85 * initialMoonDist,
                "Il satellite non deve avvicinarsi troppo al pianeta (possibile caduta): distanza minima osservata="
                        + minMoonDist + " m, soglia=" + (0.85 * initialMoonDist) + " m");
        assertTrue(maxMoonDist < 1.15 * initialMoonDist,
                "Il satellite non deve allontanarsi eccessivamente dal pianeta (possibile fuga): distanza massima osservata="
                        + maxMoonDist + " m, soglia=" + (1.15 * initialMoonDist) + " m");
        assertTrue(finalMoonDist < 1.15 * initialMoonDist,
                "Dopo i periodi orbitali completi il satellite deve essere ancora chiaramente legato al pianeta "
                        + "(distanza iniziale=" + initialMoonDist + " m, finale=" + finalMoonDist + " m)");
    }
}
