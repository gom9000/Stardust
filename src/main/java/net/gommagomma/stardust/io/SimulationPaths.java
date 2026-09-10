package net.gommagomma.stardust.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class SimulationPaths
{
    public final String simulationId;
    public final Path defaultParamsFile;
    public final Path paramsFile;
    public final Path savepointFile;
    public final Path eventsLogFile;
    public final Path runsLogFile;
    public final Path screenshotsDir;

    public SimulationPaths(String simulationId) {
        this.simulationId = simulationId;
        Path root = Paths.get(".");
        Path sim = Paths.get("simulations", simulationId);
        this.defaultParamsFile = root.resolve("parameters.txt");
        this.paramsFile = sim.resolve("parameters.txt");
        this.savepointFile = sim.resolve("savepoint.txt");
        this.eventsLogFile = sim.resolve("events.log");
        this.runsLogFile = sim.resolve("runs.log");
        this.screenshotsDir = sim.resolve("screenshots");

        try {
            Files.createDirectories(screenshotsDir);
        } catch (IOException e) {
            throw new RuntimeException("Impossibile creare la cartella della simulazione: " + sim, e);
        }
    }
}