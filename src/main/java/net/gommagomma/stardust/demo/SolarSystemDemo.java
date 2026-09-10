package net.gommagomma.stardust.demo;

import java.util.ArrayList;
import java.util.List;

import net.gommagomma.stardust.PhysicsConstants;
import net.gommagomma.stardust.SimulationConfig;
import net.gommagomma.stardust.Stardust;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;

public class SolarSystemDemo
{
	public static void main(String[] args) throws Exception
    {
        Stardust stardust = new Stardust("solar-system", "Stardust — Sistema Solare con Satelliti Principali");
        stardust.setParticles(createSolarSystem());
        stardust.start();
    }

    private static List<Particle> createSolarSystem()
    {
        List<Particle> particles = new ArrayList<>();
        double theta = 0.0;

        // Dati dei pianeti principali: { rAU, mass, density }
        double[][] planetsData = {
            { 0.387, 3.301e23, 5427.0 }, // Mercurio
            { 0.723, 4.867e24, 5243.0 }, // Venere
            { 1.000, 5.972e24, 5515.0 }, // Terra
            { 1.524, 6.417e23, 3933.0 }, // Marte
            { 5.203, 1.898e27, 1326.0 }, // Giove
            { 9.539, 5.684e26,  687.0 }, // Saturno
            { 19.18, 8.682e25, 1270.0 }, // Urano
            { 30.06, 1.024e26, 1638.0 }  // Nettuno
        };

        for (int i = 0; i < planetsData.length; i++) {
            double rAU = planetsData[i][0];
            double mass = planetsData[i][1];
            double density = planetsData[i][2];

            // 1. Creazione del pianeta madre
            Particle planet = createProtoplanet(rAU, theta, mass, 0.0, density);
            particles.add(planet);

            // Calcolo posizione e velocità cartesiane del pianeta per agganciare i satelliti
            double planetR = rAU * PhysicsConstants.AU;
            double planetX = planetR * Math.cos(theta);
            double planetY = planetR * Math.sin(theta);

            double vPlanet = Math.sqrt(PhysicsConstants.G * SimulationConfig.STAR_MASS / planetR);
            double vPlanetX = -vPlanet * Math.sin(theta);
            double vPlanetY = vPlanet * Math.cos(theta);

            // 2. Aggiunta dei satelliti specifici per pianeta
            if (Math.abs(rAU - 1.0) < 1e-4) {
                // --- TERRA ---
                // La Luna
                addSatellite(particles, planetX, planetY, vPlanetX, vPlanetY, mass, 3.844e8, 7.342e22, 3340.0, theta);

            } else if (Math.abs(rAU - 5.203) < 1e-3) {
                // --- GIOVE (Satelliti Galileiani principali) ---
                addSatellite(particles, planetX, planetY, vPlanetX, vPlanetY, mass, 4.217e8, 8.932e22, 3528.0, theta); // Io
                addSatellite(particles, planetX, planetY, vPlanetX, vPlanetY, mass, 6.711e8, 4.800e22, 3013.0, theta); // Europa
                addSatellite(particles, planetX, planetY, vPlanetX, vPlanetY, mass, 1.070e9, 1.482e23, 1942.0, theta); // Ganimede
                addSatellite(particles, planetX, planetY, vPlanetX, vPlanetY, mass, 1.883e9, 1.076e23, 1834.0, theta); // Callisto

            } else if (Math.abs(rAU - 9.539) < 1e-3) {
                // --- SATURNO ---
                addSatellite(particles, planetX, planetY, vPlanetX, vPlanetY, mass, 2.380e8, 1.345e20, 990.0, theta);  // Encelado
                addSatellite(particles, planetX, planetY, vPlanetX, vPlanetY, mass, 1.222e9, 1.345e23, 1882.0, theta); // Titano
            }
        }

        return particles;
    }

    /**
     * Metodo di supporto per aggiungere un satellite in orbita circolare attorno al pianeta madre.
     */
    private static void addSatellite(List<Particle> particles, double planetX, double planetY, 
                                     double vPlanetX, double vPlanetY, double planetMass, 
                                     double satDist, double satMass, double satDensity, double theta)
    {
        double satX = planetX + satDist * Math.cos(theta);
        double satY = planetY + satDist * Math.sin(theta);
        Vector3D satPosition = new Vector3D(satX, satY, 0.0);

        double vSatRel = Math.sqrt(PhysicsConstants.G * planetMass / satDist);
        double satVx = vPlanetX - vSatRel * Math.sin(theta);
        double satVy = vPlanetY + vSatRel * Math.cos(theta);
        Vector3D satVelocity = new Vector3D(satVx, satVy, 0.0);

        particles.add(new Particle(satPosition, satVelocity, satMass, 0.0, satDensity));
    }

    private static Particle createProtoplanet(double rAU, double theta, double mass, double charge, double density)
    {
        double r = rAU * PhysicsConstants.AU;
        Vector3D position = new Vector3D(r * Math.cos(theta), r * Math.sin(theta), 0.0);

        double v = Math.sqrt(PhysicsConstants.G * SimulationConfig.STAR_MASS / r);
        Vector3D velocity = new Vector3D(-v * Math.sin(theta), v * Math.cos(theta), 0.0);

        return new Particle(position, velocity, mass, charge, density);
    }
}