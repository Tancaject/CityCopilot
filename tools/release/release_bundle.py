#!/usr/bin/env python3
"""Seal/verify existing backend artifacts; never build, migrate or start applications."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tempfile
import uuid
import zipfile


SERVICES = {
    "core": ("core", "com.citycopilot.core.CityCopilotCoreApplication"),
    "commerce": ("commerce-service", "com.citycopilot.commerce.CityCopilotCommerceApplication"),
    "agent": ("agent", "com.citycopilot.agent.CityCopilotAgentApplication"),
    "mcp": ("mcp-server", "com.citycopilot.mcp.CityCopilotMcpServerApplication"),
}
DATABASES = ("core", "commerce", "agent")
MANIFEST = "manifest.json"


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def run(*command):
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode:
        raise ValueError(f"{command[0]} {command[1]} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def regular_file(path):
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"Expected a regular file: {path}")


def bundle_path(root, name):
    relative = PurePosixPath(name)
    if (relative.is_absolute() or not relative.parts or ".." in relative.parts
            or "\\" in name or ":" in name or relative.as_posix() != name):
        raise ValueError(f"Invalid bundle path: {name}")
    path = root.joinpath(*relative.parts)
    if not path.resolve().is_relative_to(root.resolve()):
        raise ValueError(f"Bundle path escapes its directory: {name}")
    regular_file(path)
    return path


def jar_metadata(path, expected_class):
    with zipfile.ZipFile(path) as jar:
        manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8")
        manifest = manifest.replace("\r\n ", "").replace("\n ", "")
        fields = dict(line.split(": ", 1) for line in manifest.splitlines() if ": " in line)
        if fields.get("Start-Class") != expected_class or not fields.get("Spring-Boot-Version"):
            raise ValueError(f"Not the expected executable Spring Boot jar: {path}")
        if "BOOT-INF/classes/" + expected_class.replace(".", "/") + ".class" not in jar.namelist():
            raise ValueError(f"Application class is missing in {path}")
        return {"startClass": fields["Start-Class"], "springBootVersion": fields["Spring-Boot-Version"]}


def create_bundle(repository, output, release_id, allow_dirty=False):
    repository = repository.resolve()
    output = output.resolve()
    # Git identifies the observed checkout, not the provenance of pre-existing jars.
    commit = run("git", "-C", str(repository), "rev-parse", "HEAD")
    dirty = bool(run("git", "-C", str(repository), "status", "--porcelain", "--untracked-files=normal"))
    if dirty and not allow_dirty:
        raise ValueError("Checkout has changes. Build from a reviewed checkout, or use --allow-dirty for a local candidate.")
    if output.exists():
        raise ValueError(f"Release directory already exists; use a new directory: {output}")

    inputs = []
    for service, (module, main_class) in SERVICES.items():
        jars = list((repository / "backend" / module / "target").glob("*-exec.jar"))
        if len(jars) != 1:
            raise ValueError(f"Expected exactly one backend/{module}/target/*-exec.jar; package the intended revision first.")
        regular_file(jars[0])
        metadata = jar_metadata(jars[0], main_class)
        inputs.append((jars[0], f"applications/{service}.jar", "application", {"service": service, **metadata}))
    for database in DATABASES:
        directory = repository / "deploy/mysql" / database
        scripts = sorted(directory.glob("*.sql"))
        if not any(p.name.startswith("V1__") for p in scripts) or not (directory / "beforeBaseline.sql").is_file():
            raise ValueError(f"Canonical migration chain is incomplete: {directory}")
        for script in scripts:
            inputs.append((script, f"migrations/{database}/{script.name}", "migration", {"database": database}))
    # References explain the original build/runtime defaults; they are not a runnable deployment.
    for name in ("pom.xml", "compose.yaml", "deploy/docker/backend.Dockerfile"):
        inputs.append((repository / name, f"reference/{name}", "reference", {}))

    output.mkdir(parents=True)
    entries = []
    for source, name, kind, metadata in inputs:
        regular_file(source)
        before = sha256(source)
        destination = output / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        if sha256(destination) != before or sha256(source) != before:
            raise ValueError(f"Input changed while sealing {source}; discard this incomplete candidate and build again.")
        entries.append({"path": name, "kind": kind, "bytes": destination.stat().st_size,
                        "sha256": before, **metadata})
    manifest = {"schemaVersion": 1, "releaseId": release_id, "createdAt": utc_now(),
                "checkout": {"commit": commit, "dirty": dirty, "jarProvenanceVerified": False},
                "files": entries}
    # A manifest is written last; incomplete copies never become a verified release.
    (output / MANIFEST).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    digest = sha256(output / MANIFEST)
    verify_bundle(output, digest)
    return {"releaseId": release_id, "bundle": str(output), "manifestSha256": digest,
            "fileCount": len(entries), "checkoutDirty": dirty, "jarProvenanceVerified": False}


def verify_bundle(bundle, expected_manifest_sha256):
    bundle = bundle.resolve()
    manifest_path = bundle_path(bundle, MANIFEST)
    if not re.fullmatch(r"[0-9a-f]{64}", expected_manifest_sha256):
        raise ValueError("--manifest-sha256 must be the trusted 64-character lowercase SHA-256 from the release record.")
    if sha256(manifest_path) != expected_manifest_sha256:
        raise ValueError("Release manifest differs from the trusted digest; stop deployment and obtain the approved artifact.")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest["schemaVersion"] != 1:
        raise ValueError("Unsupported release manifest schemaVersion.")
    expected = {MANIFEST}
    applications = set()
    migrations = set()
    for entry in manifest["files"]:
        name = entry["path"]
        if name in expected:
            raise ValueError(f"Duplicate manifest entry: {name}")
        expected.add(name)
        path = bundle_path(bundle, name)
        if path.stat().st_size != entry["bytes"] or sha256(path) != entry["sha256"]:
            raise ValueError(f"Artifact checksum/size differs: {name}; restore the approved bundle.")
        if entry["kind"] == "application":
            service = entry["service"]
            if service not in SERVICES or name != f"applications/{service}.jar":
                raise ValueError(f"Unexpected application: {name}")
            jar_metadata(path, SERVICES[service][1])
            applications.add(service)
        elif entry["kind"] == "migration":
            database = entry["database"]
            if database not in DATABASES or not name.startswith(f"migrations/{database}/"):
                raise ValueError(f"Unexpected migration: {name}")
            migrations.add(database)
    actual = set()
    for path in bundle.rglob("*"):
        if path.is_symlink():
            raise ValueError(f"Symlinks are not allowed in a release: {path}")
        if path.is_file():
            actual.add(path.relative_to(bundle).as_posix())
    if actual != expected or applications != set(SERVICES) or migrations != set(DATABASES):
        raise ValueError("Release files differ from the manifest or required services/migration chains are missing.")
    return manifest


def verify_image(bundle, manifest_digest, service, image):
    manifest = verify_bundle(bundle, manifest_digest)
    if not re.fullmatch(r"[^\s@]+@sha256:[0-9a-f]{64}", image):
        raise ValueError("--image must be repository@sha256:<digest>; tags are not a release identity.")
    expected = next(entry for entry in manifest["files"]
                    if entry["kind"] == "application" and entry["service"] == service)
    # Never pull or start an image. Inspect one local digest and copy from a stopped container.
    details = json.loads(run("docker", "image", "inspect", image))[0]
    container = None
    with tempfile.TemporaryDirectory(prefix="citycopilot-release-image-") as directory:
        try:
            container = run("docker", "create", "--pull=never", "--network=none",
                            "--name", "citycopilot-release-audit-" + uuid.uuid4().hex,
                            "--entrypoint", "/citycopilot-release-never-started", image)
            path = Path(directory) / "application.jar"
            run("docker", "cp", f"{container}:/app/application.jar", str(path))
            if sha256(path) != expected["sha256"]:
                raise ValueError(f"Image {image} does not contain the sealed {service} jar; do not deploy it.")
        finally:
            if container:
                run("docker", "rm", "-v", container)
    return {"checkedAt": utc_now(), "releaseId": manifest["releaseId"],
            "manifestSha256": manifest_digest, "service": service, "image": image,
            "imageId": details["Id"], "platform": details["Os"] + "/" + details["Architecture"],
            "jarSha256": expected["sha256"], "jarPathInImage": "/app/application.jar"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("create", help="Copy four prebuilt exec jars and canonical SQL into a new sealed directory (offline).")
    create.add_argument("--repository", type=Path, default=Path(__file__).resolve().parents[2])
    create.add_argument("--output", type=Path, required=True)
    create.add_argument("--release-id", required=True)
    create.add_argument("--allow-dirty", action="store_true", help="Record a local dirty-checkout candidate; never proves jar source provenance.")
    for name in ("verify", "verify-image"):
        command = commands.add_parser(name, help="Check the trusted manifest and every file" + ("; inspect a local Docker image without starting it." if name == "verify-image" else " (offline)."))
        command.add_argument("--bundle", type=Path, required=True)
        command.add_argument("--manifest-sha256", required=True)
        if name == "verify-image":
            command.add_argument("--service", choices=SERVICES, required=True)
            command.add_argument("--image", required=True)
    args = parser.parse_args()
    try:
        if args.command == "create":
            result = create_bundle(args.repository, args.output, args.release_id, args.allow_dirty)
        elif args.command == "verify-image":
            result = verify_image(args.bundle, args.manifest_sha256, args.service, args.image)
        else:
            manifest = verify_bundle(args.bundle, args.manifest_sha256)
            result = {"releaseId": manifest["releaseId"], "manifestSha256": args.manifest_sha256,
                      "fileCount": len(manifest["files"]), "verified": True}
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except (ValueError, OSError, KeyError, TypeError, zipfile.BadZipFile) as error:
        print(f"Release check failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
