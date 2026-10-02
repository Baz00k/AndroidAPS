#!/usr/bin/env python3
"""Collect build/test evidence, not a promotion approval. Requires an unused output directory."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"
BUILD_TASKS = [f":{module}:assembleFull{variant}" for module in ("app", "wear") for variant in ("Debug", "Loop")]
BUILD_TASKS += [":benchmark:assembleFullLoop"]


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def test_results(directory):
    """Count reported cases, including parameterized cases; never infer discovery from source files."""
    counts = dict(reported=0, executed=0, passed=0, failed=0, skipped=0)
    failures, errors, files = [], [], sorted(directory.glob("TEST-*.xml"))
    for file in files:
        try:
            suite = ET.parse(file).getroot()
        except (ET.ParseError, OSError) as error:
            errors.append(f"{file.name}: {error}")
            continue
        for case in suite.iter("testcase"):
            counts["reported"] += 1
            if case.find("skipped") is not None:
                counts["skipped"] += 1
            else:
                counts["executed"] += 1
                failure = case.find("failure")
                if failure is None:
                    failure = case.find("error")
                if failure is not None:
                    counts["failed"] += 1
                    failures.append(dict(classname=case.get("classname"), name=case.get("name"),
                                         message=failure.get("message")))
                else:
                    counts["passed"] += 1
    return dict(counts=counts, failures=failures, errors=errors, xml_files=len(files))


def task_outcome(log, task):
    matches = re.findall(r"^> Task " + re.escape(task) + r"(?: (\S+))?$", log, re.MULTILINE)
    return (matches[-1] or "EXECUTED") if matches else "NOT_OBSERVED"


def manifest_identity(xml):
    manifest = ET.fromstring(xml)
    application = manifest.find("application")
    if application is None:
        raise ValueError("APK has no application element")
    profileable = application.find("profileable")
    return dict(application_id=manifest.get("package"), version_code=manifest.get(ANDROID + "versionCode"),
                version_name=manifest.get(ANDROID + "versionName"),
                debuggable=application.get(ANDROID + "debuggable", "false") == "true",
                profileable=profileable is not None and profileable.get(ANDROID + "shell") == "true")


def signer_fingerprints(text):
    return sorted(set(re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", text, re.MULTILINE)))


def evidence_errors(report):
    errors = [f"Command failed: {command['name']}" for command in report["commands"] if command["exit_code"]]
    tasks = report["tests"]
    if not tasks or sum(task["counts"]["executed"] for task in tasks) == 0:
        errors.append("No executed unit tests reported")
    for task in tasks:
        errors.extend(f"{task['task']}: {error}" for error in task["errors"])
        if task["counts"]["failed"]:
            errors.append(f"{task['task']}: failing tests")
        if task["outcome"] in ("NOT_OBSERVED", "FAILED", "SKIPPED", "FROM-CACHE", "UP-TO-DATE"):
            errors.append(f"{task['task']}: not freshly verified ({task['outcome']})")
        if task["outcome"] == "EXECUTED" and not task["xml_files"] and task.get("compiled_class_files", 1):
            errors.append(f"{task['task']}: missing test XML")
    for module in ("app", "wear", "benchmark"):
        for variant in (("loop",) if module == "benchmark" else ("debug", "loop")):
            apks = [apk for apk in report["apks"] if apk["module"] == module and apk["variant"] == variant]
            if len(apks) != 1:
                errors.append(f"{module}/full/{variant}: expected exactly one APK")
                continue
            apk = apks[0]
            if not apk.get("identity") or not apk.get("signers"):
                errors.append(f"{module}/{variant}: missing identity or verified signer")
                continue
            identity = apk["identity"]
            if module != "benchmark" and identity["application_id"] != "info.nightscout.androidaps":
                errors.append(f"{module}/{variant}: unexpected application ID")
            if module != "benchmark" and identity["debuggable"] != (variant == "debug"):
                errors.append(f"{module}/{variant}: unexpected debuggability")
            if module == "app" and variant == "loop" and not identity["profileable"]:
                errors.append("app/loop: not shell-profileable")
    app_signers = {tuple(apk.get("signers", [])) for apk in report["apks"] if apk["module"] in ("app", "wear")}
    if len(app_signers) != 1:
        errors.append("Phone/watch Debug/Loop signers do not match")
    return errors


class Recorder:
    def __init__(self, output):
        self.output = output
        self.commands = []

    def run(self, name, argv, cwd=ROOT):
        print(f"[{name}] {' '.join(map(str, argv))}", flush=True)
        started = datetime.now(timezone.utc).isoformat()
        log = self.output / f"{name}.log"
        try:
            with log.open("w") as stream:
                result = subprocess.run(list(map(str, argv)), cwd=cwd, stdout=stream, stderr=subprocess.STDOUT, text=True)
            code, text = result.returncode, log.read_text()
        except OSError as error:
            code, text = 127, str(error)
            log.write_text(text)
        self.commands.append(dict(name=name, argv=list(map(str, argv)), cwd=str(cwd), started_at=started,
                                  finished_at=datetime.now(timezone.utc).isoformat(), exit_code=code))
        return code, text


def sdk_packages(sdk):
    # Installed package IDs and revisions, including exact system-image/emulator revisions.
    packages = []
    for path in sorted(sdk.rglob("source.properties")):
        values = dict(line.split("=", 1) for line in path.read_text().splitlines() if "=" in line)
        packages.append(dict(path=str(path.relative_to(sdk)), properties=values))
    return packages


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="New evidence directory outside the checkout")
    parser.add_argument("--allow-dirty", action="store_true", help="Diagnostic only; record a non-clean source snapshot")
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"), default="arm64-v8a")
    parser.add_argument("--emulator-serial", help="Read-only emulator metadata; does not install or run smoke tests")
    parser.add_argument("--gradle-arg", action="append", default=[], help="Non-secret resource/configuration option; repeatable")
    args = parser.parse_args()
    output = args.output.resolve()
    if output.is_relative_to(ROOT) or output.exists():
        parser.error("Use a NEW output directory OUTSIDE the checkout (prevents stale evidence)")
    dirty = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True)
    if dirty and not args.allow_dirty:
        parser.error("Checkout is not clean; commit changes or use --allow-dirty for diagnostic evidence only")
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "/nonexistent")
    if not sdk.is_dir():
        parser.error("Set ANDROID_HOME to the installed Android SDK")
    build_tools = sorted((sdk / "build-tools").glob("*/apksigner"), key=lambda path: [int(n) for n in re.findall(r"\d+", path.parent.name)])
    analyzer = sdk / "cmdline-tools/latest/bin/apkanalyzer"
    if not build_tools or not analyzer.is_file() or not shutil.which("java"):
        parser.error("JDK, Android build-tools (apksigner) and cmdline-tools/latest (apkanalyzer) are required")
    # All arguments go to the report; passwords must never be passed to this entry point.
    if any(re.search(r"password|secret|token|signing", arg, re.I) for arg in args.gradle_arg):
        parser.error("Signing/secret arguments are not accepted; use a securely provisioned local debug keystore")
    if args.emulator_serial and not re.fullmatch(r"emulator-\d+", args.emulator_serial):
        parser.error("Metadata capture is restricted to an explicitly named emulator")
    output.mkdir(parents=True)
    recorder = Recorder(output)
    report = dict(schema_version=1, commit=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                  dirty_status=dirty, abi=args.abi, commands=recorder.commands, sdk_packages=sdk_packages(sdk), tests=[], apks=[],
                  promotion_status="BLOCKED", remaining_gates=["Platform/paired smoke fixtures (#99)",
                  "Device measurements and maintainer-approved tolerances (#100)", "Update/restore validation (#101)"])
    (output / "source.patch").write_bytes(subprocess.check_output(["git", "diff", "HEAD", "--binary"], cwd=ROOT))
    # Include hashes of untracked inputs too; a dirty run cannot masquerade as evidence for HEAD.
    untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "-z"], cwd=ROOT).decode().split("\0")
    report["untracked_inputs"] = {name: sha256(ROOT / name) for name in untracked if name and (ROOT / name).is_file()}
    source_fingerprint = sha256(output / "source.patch"), report["untracked_inputs"], dirty, report["commit"]
    recorder.run("java-version", ["java", "-version"])
    recorder.run("gradle-version", [ROOT / "gradlew", "--version"])
    if args.emulator_serial:
        recorder.run("emulator-properties", [sdk / "platform-tools/adb", "-s", args.emulator_serial, "shell", "getprop"])
        recorder.run("emulator-version", [sdk / "emulator/emulator", "-version"])
        report["emulator_serial"] = args.emulator_serial
    gradle = [ROOT / "gradlew", "--console=plain", "--no-build-cache", "--max-workers=3", *args.gradle_arg]
    # Separate clean invocation: none of its output can be confused with cached reports.
    clean_code, _ = recorder.run("clean", [*gradle, "clean"])
    build_log = ""
    if not clean_code:
        recorder.run("test-inventory", [*gradle, "-I", ROOT / "tools/validation/baseline.init.gradle.kts",
                                        f"-PvalidationEvidenceDir={output}", "validationBaselineInventory"])
        _, build_log = recorder.run("build-tests", [*gradle, "-I", ROOT / "tools/validation/baseline.init.gradle.kts",
                                    f"-PaapsTargetAbi={args.abi}", "--continue", *BUILD_TASKS, "validationBaselineTests"])
        # buildSrc is a separate build: root clean does not remove its prior XML.
        buildsrc_clean_code, _ = recorder.run("buildsrc-clean", [*gradle, "-p", ROOT / "buildSrc", "clean"])
        buildsrc_log = ""
        if not buildsrc_clean_code:
            _, buildsrc_log = recorder.run("buildsrc-tests", [*gradle, "-p", ROOT / "buildSrc", "test"])
        catalog_path = output / "test-tasks.json"
        catalog = json.loads(catalog_path.read_text()) if catalog_path.exists() else []
        catalog.append(dict(task=":buildSrc:test", xml_dir=str(ROOT / "buildSrc/build/test-results/test"),
                            class_dirs=[str(ROOT / "buildSrc/build/classes/kotlin/test")]))
        for entry in catalog:
            directory = Path(entry["xml_dir"])
            result = test_results(directory)
            result["compiled_class_files"] = sum(len(list(Path(path).rglob("*.class"))) for path in entry["class_dirs"])
            log_task = ":test" if entry["task"] == ":buildSrc:test" else entry["task"]
            result.update(task=entry["task"], outcome=task_outcome(buildsrc_log if log_task == ":test" else build_log, log_task))
            report["tests"].append(result)
            if directory.is_dir():
                shutil.copytree(directory, output / "test-results" / entry["task"].strip(":").replace(":", "/"))
        for module in ("app", "wear", "benchmark"):
            for variant in (("loop",) if module == "benchmark" else ("debug", "loop")):
                for path in sorted((ROOT / module / "build/outputs/apk/full" / variant).glob("*.apk")):
                    name = f"{module}-{variant}-{path.name}"
                    destination = output / "apks" / name
                    destination.parent.mkdir(exist_ok=True)
                    shutil.copy2(path, destination)
                    apk = dict(module=module, variant=variant, file=f"apks/{name}", sha256=sha256(destination), bytes=destination.stat().st_size)
                    code, xml = recorder.run(f"{module}-{variant}-manifest", [analyzer, "manifest", "print", destination])
                    if not code:
                        try:
                            apk["identity"] = manifest_identity(xml)
                        except (ET.ParseError, ValueError) as error:
                            apk["identity_error"] = str(error)
                    code, signer = recorder.run(f"{module}-{variant}-signer", [build_tools[-1], "verify", "--verbose", "--print-certs", destination])
                    apk["signers"] = signer_fingerprints(signer) if not code else []
                    report["apks"].append(apk)
    final_patch = subprocess.check_output(["git", "diff", "HEAD", "--binary"], cwd=ROOT)
    final_untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "-z"], cwd=ROOT).decode().split("\0")
    final_inputs = {name: sha256(ROOT / name) for name in final_untracked if name and (ROOT / name).is_file()}
    final_dirty = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True)
    final_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    report["source_changed_during_run"] = source_fingerprint != (hashlib.sha256(final_patch).hexdigest(), final_inputs, final_dirty, final_commit)
    report["evidence_errors"] = evidence_errors(report)
    if report["source_changed_during_run"]:
        report["evidence_errors"].append("Source changed during evidence capture; repeat from a fixed checkout")
    report["build_test_status"] = "FAILED" if report["evidence_errors"] else "PASSED"
    (output / "summary.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Build/test evidence: {report['build_test_status']}; promotion: BLOCKED. See {output / 'summary.json'}")
    return 1 if report["evidence_errors"] else 0


if __name__ == "__main__":
    sys.exit(main())
