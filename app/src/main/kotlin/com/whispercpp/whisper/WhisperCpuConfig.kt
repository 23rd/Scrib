package com.whispercpp.whisper

import android.util.Log
import java.io.BufferedReader
import java.io.FileReader

object WhisperCpuConfig {
    val preferredThreadCount: Int
        // Always use at least 2 threads:
        get() = CpuInfo.getHighPerfCpuCount().coerceAtLeast(2)
}

private class CpuInfo(private val lines: List<String>) {
    private fun getHighPerfCpuCount(): Int =
        read("capacities") { getHighPerfCpuCountByCapacities() }
            ?: read("frequencies") { getHighPerfCpuCountByFrequencies() }
            ?: getHighPerfCpuCountByVariant()

    private fun read(what: String, count: () -> Int): Int? = try {
        count()
    } catch (e: Exception) {
        Log.d(LOG_TAG, "Couldn't read CPU $what", e)
        null
    }

    // The scheduler's own measure of how much work a core can do, with the biggest one at 1024.
    // It folds in the core design as well as its top clock, so a real LITTLE core lands far below
    // its big siblings, while a big core that merely clocks lower stays right next to them.
    private fun getHighPerfCpuCountByCapacities(): Int =
        getCpuValues(property = "processor") { getCpuCapacity(it.toInt()) }
            .also { Log.d(LOG_TAG, "Binned cpu capacities (capacity, count): ${it.binnedValues()}") }
            .countNearMax(CAPACITY_SHARE)

    private fun getHighPerfCpuCountByFrequencies(): Int =
        getCpuValues(property = "processor") { getMaxCpuFrequency(it.toInt()) }
            .also { Log.d(LOG_TAG, "Binned cpu frequencies (frequency, count): ${it.binnedValues()}") }
            .countDroppingMin()

    private fun getHighPerfCpuCountByVariant(): Int =
        getCpuValues(property = "CPU variant") { it.substringAfter("0x").toInt(radix = 16) }
            .also { Log.d(LOG_TAG, "Binned cpu variants (variant, count): ${it.binnedValues()}") }
            .countKeepingMin()

    private fun List<Int>.binnedValues() = groupingBy { it }.eachCount()

    private fun getCpuValues(property: String, mapper: (String) -> Int) = lines
        .asSequence()
        .filter { it.startsWith(property) }
        .map { mapper(it.substringAfter(':').trim()) }
        .sorted()
        .toList()


    // Every core close enough to the fastest one earns a thread: whisper splits each matmul into
    // equal shares and waits for the last core to finish, so a core that keeps up is worth having
    // and one that lags holds up all the others. Keeping only the topmost bin would leave a CPU
    // like the Snapdragon 8 Elite -- where six of the eight big cores merely clock a little lower
    // than the other two -- running on a quarter of the cores it has.
    private fun List<Int>.countNearMax(share: Double): Int {
        val cutoff = max() * share
        return count { it >= cutoff }
    }

    // A uniform CPU -- every core the same speed, as on x86 and on SoCs without big.LITTLE --
    // has no slow cores to drop, so all of them are worth using.
    private fun List<Int>.countDroppingMin(): Int {
        val min = min()
        val faster = count { it > min }
        return if (faster > 0) faster else size
    }

    private fun List<Int>.countKeepingMin(): Int {
        val min = min()
        return count { it == min }
    }

    companion object {
        private const val LOG_TAG = "WhisperCpuConfig"

        // A LITTLE core sits at about a quarter of the capacity of a big one, while a big core on
        // a lower clock stays above two thirds, so half of the maximum tells the two apart. Bare
        // frequencies cannot: the gap between the clusters of a Snapdragon 660 is narrower than
        // the one between the big cores of a Snapdragon 8 Elite.
        private const val CAPACITY_SHARE = 0.5

        fun getHighPerfCpuCount(): Int = try {
            readCpuInfo().getHighPerfCpuCount()
        } catch (e: Exception) {
            Log.d(LOG_TAG, "Couldn't read CPU info", e)
            // Our best guess -- just return the # of CPUs minus 4.
            (Runtime.getRuntime().availableProcessors() - 4).coerceAtLeast(0)
        }

        private fun readCpuInfo() = CpuInfo(
            BufferedReader(FileReader("/proc/cpuinfo"))
                .useLines { it.toList() }
        )

        private fun getMaxCpuFrequency(cpuIndex: Int): Int {
            val path = "/sys/devices/system/cpu/cpu${cpuIndex}/cpufreq/cpuinfo_max_freq"
            val maxFreq = BufferedReader(FileReader(path)).use { it.readLine() }
            return maxFreq.toInt()
        }

        private fun getCpuCapacity(cpuIndex: Int): Int {
            val path = "/sys/devices/system/cpu/cpu${cpuIndex}/cpu_capacity"
            val capacity = BufferedReader(FileReader(path)).use { it.readLine() }
            return capacity.toInt()
        }
    }
}
