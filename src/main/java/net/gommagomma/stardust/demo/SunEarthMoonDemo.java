package net.gommagomma.stardust.demo;
import java.util.ArrayList;
import java.util.List;

import net.gommagomma.stardust.PhysicsConstants;
import net.gommagomma.stardust.SimulationParams;
import net.gommagomma.stardust.Stardust;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;

public class SunEarthMoonDemo
{
	public static void main(String[] args) throws Exception
    {
        Stardust stardust = new Stardust("sole-terra-luna", "Stardust — Sistema Gerarchico");
        stardust.setParticles(createHierarchicalSystem(stardust.getContext()));
        stardust.start();
    }


    private static List<Particle> createHierarchicalSystem(SimulationParams params)
    {
        List<Particle> particles = new ArrayList<>();
        
        // 2. Pianeta in orbita attorno alla stella (es. a 1 AU)
        double starPlanetDist = 1.0 * PhysicsConstants.AU;
        double vPlanet = Math.sqrt(PhysicsConstants.G * params.centralStarMass / starPlanetDist);
        Particle planet = new Particle(
                new Vector3D(starPlanetDist, 0, 0),
                new Vector3D(0, vPlanet, 0),
                5.972e24, 0.0, 5500.0
        );

        // 3. Satellite in orbita attorno al pianeta (es. a 400.000 km)
        double planetMoonDist = 4.0e8;
        double vMoonRel = Math.sqrt(PhysicsConstants.G * planet.getMass() / planetMoonDist);
        Particle satellite = new Particle(
                new Vector3D(starPlanetDist + planetMoonDist, 0, 0),
                new Vector3D(0, vPlanet + vMoonRel, 0),
                7.342e22, 0.0, 3340.0
        );

        particles.add(planet);
        particles.add(satellite);

        return particles;
    }
}