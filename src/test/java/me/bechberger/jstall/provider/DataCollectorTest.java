package me.bechberger.jstall.provider;

import me.bechberger.jstall.provider.requirement.*;
import me.bechberger.jstall.util.JMXDiagnosticHelper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DataCollectorTest {

    private static DataRequirements requirementsOf(DataRequirement... requirements) {
        try {
            Constructor<DataRequirements> ctor = DataRequirements.class.getDeclaredConstructor(Set.class);
            ctor.setAccessible(true);
            return ctor.newInstance(new HashSet<>(Set.of(requirements)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final class StubRequirement implements DataRequirement {
        private final String type;
        private final CollectionSchedule schedule;
        private final boolean fail;
        private final String payload;

        private StubRequirement(String type, CollectionSchedule schedule, boolean fail, String payload) {
            this.type = type;
            this.schedule = schedule;
            this.fail = fail;
            this.payload = payload;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public CollectionSchedule schedule() {
            return schedule;
        }

        @Override
        public CollectedData collect(JMXDiagnosticHelper helper, int sampleIndex) throws IOException {
            if (fail) {
                throw new IOException("simulated failure for " + type);
            }
            return new CollectedData(System.currentTimeMillis(), payload + "-" + sampleIndex, Map.of());
        }

        @Override
        public void persist(ZipOutputStream zipOut, String pidPath, List<CollectedData> samples) {
        }

        @Override
        public List<CollectedData> load(ZipFile zipFile, String pidPath) {
            return List.of();
        }
    }

    @Test
    void oneTimeFailureDoesNotAbortOtherCollections() throws Exception {
        DataRequirement failingOneTime = new StubRequirement(
            "vm_vitals",
            CollectionSchedule.once(),
            true,
            ""
        );
        DataRequirement healthyOneTime = new StubRequirement(
            "vm-uptime",
            CollectionSchedule.once(),
            false,
            "uptime"
        );
        DataRequirement healthyInterval = new StubRequirement(
            "gc-heap-info",
            CollectionSchedule.intervals(2, 1),
            false,
            "heap"
        );

        DataRequirements requirements = requirementsOf(
            failingOneTime,
            healthyOneTime,
            healthyInterval
        );

        DataCollector collector = new DataCollector(null, requirements);
        Map<DataRequirement, List<CollectedData>> collected = collector.collectAll();

        assertTrue(collected.containsKey(failingOneTime));
        assertEquals(1, collected.get(failingOneTime).size());
        assertTrue(collected.get(failingOneTime).get(0).metadata().containsKey("error"));

        assertTrue(collected.containsKey(healthyOneTime));
        assertEquals(1, collected.get(healthyOneTime).size());
        assertEquals("uptime-0", collected.get(healthyOneTime).get(0).rawData());

        assertTrue(collected.containsKey(healthyInterval));
        assertEquals(2, collected.get(healthyInterval).size());
        assertEquals("heap-0", collected.get(healthyInterval).get(0).rawData());
        assertEquals("heap-1", collected.get(healthyInterval).get(1).rawData());
    }
}
