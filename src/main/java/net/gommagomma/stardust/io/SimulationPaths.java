package net.gommagomma.stardust.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class SimulationPaths
{
    public final String simulationId;
    public final Path root;
    public final Path savepointFile;
    public final Path eventsLogFile;
    public final Path runsLogFile;
    public final Path screenshotsDir;

    public SimulationPaths(String simulationId) {
        this.simulationId = simulationId;
        this.root = Paths.get("simulations", simulationId);
        this.savepointFile = root.resolve("savepoint.txt");
        this.eventsLogFile = root.resolve("events.log");
        this.runsLogFile = root.resolve("runs.log");
        this.screenshotsDir = root.resolve("screenshots");

        try {
            Files.createDirectories(screenshotsDir);
        } catch (IOException e) {
            throw new RuntimeException("Impossibile creare la cartella della simulazione: " + root, e);
        }
    }
}