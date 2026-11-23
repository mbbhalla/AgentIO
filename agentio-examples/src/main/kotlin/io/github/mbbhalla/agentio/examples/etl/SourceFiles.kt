package io.github.mbbhalla.agentio.examples.etl

import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The CSV files an ETL run may read: every `.csv` file directly in [directory], known by file name.
 * Agents name sources by file name; only the pipeline turns a name into a path.
 */
class SourceFiles private constructor(
    val directory: Path,
    private val files: Map<String, Path>,
) {
    /** File names in name order. */
    val names: List<String> = files.keys.sorted()

    /** The absolute path of the source called [name]. */
    fun path(name: String): Path = requireNotNull(files[name]) { "Unknown source file '$name'; the source files are $names" }

    /** Whether [path], once made absolute and normalized, is one of the source files. */
    fun isSource(path: Path): Boolean = path.toAbsolutePath().normalize() in files.values

    companion object {
        private const val CSV_GLOB = "*.csv"

        /** @throws IllegalArgumentException if [directory] does not exist or holds no `.csv` file */
        fun of(directory: Path): SourceFiles {
            val absolute = directory.toAbsolutePath().normalize()
            require(absolute.isDirectory()) { "Source directory does not exist: $absolute" }
            val files = absolute.listDirectoryEntries(CSV_GLOB).filter { it.isRegularFile() }.associateBy { it.name }
            require(files.isNotEmpty()) { "No .csv files in $absolute" }
            return SourceFiles(absolute, files)
        }
    }
}
