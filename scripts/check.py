#!/usr/bin/env python3
"""End-to-end checks; measurements are validated structurally, never by speed."""
import argparse
import csv
import io
import json
import math
import pathlib
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("--scala-version", default="3.3.8")
parser.add_argument("--skip-build", action="store_true")
parser.add_argument("--incremental", action="store_true")
parser.add_argument("--lto", choices=("none", "thin", "full"))
arguments = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]
configuration = [f"++{arguments.scala_version}"]
if arguments.lto:
    configuration.append(
        'set LocalProject("nativeExamples") / Compile / nativeConfig ~= (_.withLTO('
        f'scala.scalanative.build.LTO("{arguments.lto}")))')
if not arguments.skip_build:
    subprocess.run(["sbt", "--batch", "-Dsbt.supershell=false", "; ".join(configuration + [
        "compilerPlugin/test", "runtimeJVM/test", "runtimeJS/compile", "sbtScalaBenchmark/compile",
        "jvmExamples/Jmh/compile", "nativeExamples/Jmh/run -l"
    ])], cwd=root, check=True)
binary = root / "target/out/native0.5" / f"scala-{arguments.scala_version}" / "nativeexamples/nativeexamples"
assert binary.is_file(), binary

def run(*args, success=True):
    result = subprocess.run([str(binary), *args], text=True, capture_output=True, timeout=60)
    assert (result.returncode == 0) == success, result.stdout + result.stderr
    return result

listed = run("-l").stdout
assert "LifecycleBenchmark.sum" in listed
assert "InvocationBenchmark.invoke" in listed
assert "JmhSourceBenchmark.execute" in listed
assert "size: 16, 64" in run("-lp").stdout
with tempfile.TemporaryDirectory(prefix="native-jmh-check-") as directory:
    output = pathlib.Path(directory) / "results.json"
    run("LifecycleBenchmark.sum", "-bm", "all", "-p", "size=16", "-wi", "1", "-i", "3",
        "-w", "10ms", "-r", "20ms", "-f", "2", "-t", "2", "-rff", str(output))
    results = json.loads(output.read_text())
    assert {result["mode"] for result in results} == {"thrpt", "avgt", "sample", "ss"}
    for result in results:
        assert result["threads"] == 2 and result["forks"] == 2
        assert result["params"] == {"size": "16"}
        metric = result["primaryMetric"]
        assert math.isfinite(metric["score"]) and metric["score"] >= 0
        if result["mode"] != "ss":
            assert metric["score"] > 0
        if result["mode"] == "sample":
            assert len(metric["rawDataHistogram"]) == 2
        else:
            assert [len(fork) for fork in metric["rawData"]] == [3, 3]
        assert result["native"]["scalaVersion"] == arguments.scala_version
        if arguments.lto:
            assert result["native"]["lto"] == arguments.lto
    # The example asserts exactly seven invocations per shot, all fixture calls,
    # and twenty-one total invocations when the trial is torn down.
    run("InvocationBenchmark.invoke", "-f", "1")
    run("ForkIsolationBenchmark.execute")
    run("JmhSourceBenchmark.execute")
    run("ParameterBenchmark", "-p", "mode=AverageTime")
    assert "intentional benchmark failure" in run("FailureBenchmark.fail", success=False).stderr
    run("TimeoutBenchmark.block", success=False)
    run("LifecycleBenchmark.explicitBlackhole", "-p", "size=64", "-wi", "0", "-i", "1", "-r", "10ms", "-f", "0")
    output = pathlib.Path(directory) / "results.csv"
    run("LifecycleBenchmark.sum", "-p", "size=16", "-wi", "0", "-i", "1", "-r", "10ms", "-f", "1", "-rf", "csv", "-rff", str(output))
    rows = list(csv.DictReader(io.StringIO(output.read_text())))
    assert len(rows) == 1 and rows[0]["Param: size"] == "16"
    run("-i", "0", success=False)
    run("-prof", "gc", success=False)
    run("DoesNotExist", success=False)
print("Native JMH integration checks passed")

if arguments.incremental:
    temporary = root / "examples/shared/src/main/scala/scala/scalanative/jmh/examples/IncrementalProbe.scala"
    assert not temporary.exists(), temporary
    products = binary.parent / "classes/scala/scalanative/jmh/examples"
    inventory = binary.parent / "resource_managed/main/native-jmh/benchmarks.list"

    def compile_inventory():
        subprocess.run(["sbt", "--batch", "-Dsbt.supershell=false", "; ".join(configuration + ["nativeExamples/benchmarkInventory"])], cwd=root, check=True)

    def assert_product(name, present):
        module = name + "$nativejmh$bootstrapper$"
        assert (("." + module + "\n") in inventory.read_text()) == present
        assert (products / (module + ".class")).exists() == present
        assert (products / (module + ".nir")).exists() == present

    try:
        for name, method in [("IncrementalFirst", "first"), ("IncrementalSecond", "second")]:
            temporary.write_text("package scala.scalanative.jmh.examples\n"
                                 "import org.openjdk.jmh.annotations._\n"
                                 f"class {name} {{ @Benchmark def {method}(): Int = 1 }}\n")
            compile_inventory()
            assert_product(name, True)
            if name == "IncrementalSecond":
                assert_product("IncrementalFirst", False)
    finally:
        temporary.unlink()
        compile_inventory()
    assert_product("IncrementalFirst", False)
    assert_product("IncrementalSecond", False)
    print("Native JMH incremental discovery checks passed")
