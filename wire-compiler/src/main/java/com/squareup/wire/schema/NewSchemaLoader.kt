/*
 * Copyright 2018 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.wire.schema

import com.google.common.io.Closer
import com.squareup.wire.schema.SchemaLoader.DESCRIPTOR_PROTO
import com.squareup.wire.schema.internal.parser.ProtoParser
import okio.buffer
import okio.source
import java.io.Closeable
import java.io.IOException
import java.nio.file.FileSystem
import java.util.ArrayDeque

/**
 * Load proto files and their transitive dependencies and parse them. Keep track of which files were
 * loaded from where so that we can use that information later when deciding what to generate.
 */
class NewSchemaLoader(
  private val fs: FileSystem,

  /** See [com.squareup.wire.WireRun.sourcePath]. */
  private val sourcePath: List<String>,

  /** See [com.squareup.wire.WireRun.protoPath]. */
  private val protoPath: List<String> = listOf()
) : Closeable {
  private val closer = Closer.create()

  /** Errors accumulated by this load. */
  private val errors = mutableListOf<String>()

  /** Files loaded by their relative paths. This is the same path that we use in imports! */
  private val loaded = mutableMapOf<String, ProtoFile>()

  /** Source files that were loaded. Used to differentiate sources from protoPath elements. */
  lateinit var sourceLocationPaths: Set<String>

  /** Working backlog of imported .proto files to load. */
  private val imports = ArrayDeque<String>()

  @Throws(IOException::class)
  fun load(): List<ProtoFile> {
    check(loaded.isEmpty()) { "do not reuse instances of this class" }

//    val resourceUri = SchemaLoader::class.java.classLoader.getResource(DESCRIPTOR_PROTO).toURI()
//    val env = mapOf("create" to "true")
//    val jarFileSystem = FileSystems.newFileSystem(resourceUri, env)
//    val resourceRoots =
//        allRoots(closer, listOf(jarFileSystem.getPath(DESCRIPTOR_PROTO).toString()))
//    for (resourceRoot in resourceRoots) {
//      for (locationAndPath in resourceRoot.allProtoFiles()) {
//        load(locationAndPath)
//      }
//    }
//    loaded[DESCRIPTOR_PROTO] = loadDescriptorProto()

    // Load all of the sources, discovering imports as we go.
    val mutableSourceLocationPaths = mutableSetOf<String>()
    val sourceRoots = allRoots(closer, sourcePath)
    for (sourceRoot in sourceRoots) {
      for (locationAndPath in sourceRoot.allProtoFiles()) {
        load(locationAndPath)
        mutableSourceLocationPaths += locationAndPath.location.path()
      }
    }
    if (mutableSourceLocationPaths.isEmpty()) {
      errors += "no sources"
    }
    sourceLocationPaths = mutableSourceLocationPaths.toSet()

    // Load the imported files next.
    val protoPathRoots = allRoots(closer, protoPath)
    while (true) {
      val import = imports.poll() ?: break
      if (loaded[import] != null) continue // Already loaded.

      for (protoPathRoot in protoPathRoots) {
        val locationAndPath = protoPathRoot.resolve(import) ?: continue
        load(locationAndPath)
      }
    }

    if (errors.isNotEmpty()) {
      throw IllegalArgumentException(errors.joinToString(separator = "\n"))
    }

    return loaded.values.toList()
  }

  /**
   * Returns Google's protobuf descriptor, which defines standard options like default, deprecated,
   * and java_package. If the user has provided their own version of the descriptor proto, that is
   * preferred.
   */
  @Throws(IOException::class)
  fun loadDescriptorProto(): ProtoFile {
    val resourceAsStream = SchemaLoader::class.java.getResourceAsStream("/$DESCRIPTOR_PROTO")
    resourceAsStream.source().buffer().use { source ->
      val data = source.readUtf8()
      val location = Location.get(DESCRIPTOR_PROTO)
      val element = ProtoParser.parse(location, data)
      return ProtoFile.get(element)
    }
  }

  private fun load(locationAndPath: LocationAndPath) {
    val protoFile = locationAndPath.parse()
    val importPath = protoFile.importPath()

    if (locationAndPath.location.path() != importPath
        && !locationAndPath.location.path().endsWith("/$importPath")) {
      errors += "expected ${locationAndPath.path} to have a path ending with $importPath"
    }

    loaded[importPath] = protoFile
    imports.addAll(protoFile.imports())
  }

  /** Convert `pathStrings` into roots that can be searched. */
  private fun allRoots(closer: Closer, pathStrings: List<String>): List<Root> {
    val result = mutableListOf<Root>()
    for (pathString in pathStrings) {
      val path = fs.getPath(pathString)
      try {
        result += path.roots(closer)
      } catch (e: IllegalArgumentException) {
        errors += e.message!!
      }
    }
    return result
  }

  override fun close() {
    return closer.close()
  }
}

/**
 * Returns a path like `squareup/dinosaurs/Dinosaur.proto` for a file based on its package name
 * (like `squareup.dinosaurs`) and its file name (like `Dinosaur.proto`).
 */
internal fun ProtoFile.importPath() : String {
  val filename = location().path().substringAfterLast('/')
  return packageName().replace('.', '/') + "/" + filename
}
