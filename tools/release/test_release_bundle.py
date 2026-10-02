"""Focused release-file checks. No Maven, Docker daemon, database or provider calls."""

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import release_bundle as release


class ReleaseBundleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="citycopilot-release-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = self.root / "repository"
        for module, main_class in release.SERVICES.values():
            jar = self.repository / "backend" / module / "target" / "fixture-exec.jar"
            jar.parent.mkdir(parents=True)
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nSpring-Boot-Version: fixture\nStart-Class: " + main_class + "\n")
                archive.writestr("BOOT-INF/classes/" + main_class.replace(".", "/") + ".class", b"fixture-class")
        for database in release.DATABASES:
            directory = self.repository / "deploy/mysql" / database
            directory.mkdir(parents=True)
            (directory / "V1__fixture.sql").write_text("CREATE TABLE fixture (id INT);", encoding="utf-8")
            (directory / "beforeBaseline.sql").write_text("SELECT 1;", encoding="utf-8")
        for name in ("pom.xml", "compose.yaml", "deploy/docker/backend.Dockerfile"):
            path = self.repository / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture", encoding="utf-8")
        self.bundle = self.root / "sealed"
        with patch.object(release, "run", side_effect=["fixture-commit", ""]):
            self.result = release.create_bundle(self.repository, self.bundle, "fixture")
        self.digest = self.result["manifestSha256"]

    def test_bundle_keeps_original_artifacts_after_checkout_changes(self):
        (self.repository / "deploy/mysql/core/V1__fixture.sql").write_text("modified after release", encoding="utf-8")
        manifest = release.verify_bundle(self.bundle, self.digest)
        self.assertFalse(manifest["checkout"]["jarProvenanceVerified"])
        self.assertEqual(13, len(manifest["files"]))
        self.assertIn("CREATE TABLE", (self.bundle / "migrations/core/V1__fixture.sql").read_text())

    def test_changed_jar_sql_and_manifest_are_rejected(self):
        for name in ("applications/core.jar", "migrations/core/V1__fixture.sql", "manifest.json"):
            with self.subTest(name=name):
                path = self.bundle / name
                original = path.read_bytes()
                path.write_bytes(original + b"tampered")
                with self.assertRaisesRegex(ValueError, "differs"):
                    release.verify_bundle(self.bundle, self.digest)
                path.write_bytes(original)

    def test_unlisted_migration_is_rejected(self):
        (self.bundle / "migrations/core/V2__unapproved.sql").write_text("SELECT 2;", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "differ"):
            release.verify_bundle(self.bundle, self.digest)

    def test_missing_file_and_escaping_manifest_path_are_rejected(self):
        (self.bundle / "applications/core.jar").unlink()
        with self.assertRaisesRegex(ValueError, "regular file"):
            release.verify_bundle(self.bundle, self.digest)
        manifest_path = self.bundle / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["files"][0]["path"] = "../outside.jar"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Invalid bundle path"):
            release.verify_bundle(self.bundle, release.sha256(manifest_path))

    def test_dirty_checkout_and_existing_release_require_explicit_handling(self):
        with patch.object(release, "run", side_effect=["fixture-commit", " M changed.java"]):
            with self.assertRaisesRegex(ValueError, "Checkout has changes"):
                release.create_bundle(self.repository, self.root / "dirty", "dirty")
        with patch.object(release, "run", side_effect=["fixture-commit", ""]):
            with self.assertRaisesRegex(ValueError, "already exists"):
                release.create_bundle(self.repository, self.bundle, "replacement")

    def test_wrong_service_jar_is_rejected(self):
        core = self.repository / "backend/core/target/fixture-exec.jar"
        agent = self.repository / "backend/agent/target/fixture-exec.jar"
        core.write_bytes(agent.read_bytes())
        with patch.object(release, "run", side_effect=["fixture-commit", ""]):
            with self.assertRaisesRegex(ValueError, "expected executable"):
                release.create_bundle(self.repository, self.root / "wrong-service", "wrong-service")

    def test_image_tag_is_rejected_before_docker_access(self):
        with patch.object(release, "run") as docker:
            with self.assertRaisesRegex(ValueError, "tags are not"):
                release.verify_image(self.bundle, self.digest, "core", "registry.example/core:latest")
            docker.assert_not_called()

    def test_image_copy_is_hash_bound_and_only_created_container_is_removed(self):
        for matches in (True, False):
            commands = []

            def docker(*command):
                commands.append(command)
                if command[1:3] == ("image", "inspect"):
                    return json.dumps([{"Id": "sha256:local-id", "Os": "linux", "Architecture": "amd64"}])
                if command[1] == "create":
                    self.assertIn("--pull=never", command)
                    self.assertIn("--network=none", command)
                    return "fixture-container-id"
                if command[1] == "cp":
                    Path(command[-1]).write_bytes((self.bundle / "applications/core.jar").read_bytes() if matches else b"wrong jar")
                return ""

            with self.subTest(matches=matches), patch.object(release, "run", side_effect=docker):
                image = "registry.example/core@sha256:" + "a" * 64
                if matches:
                    receipt = release.verify_image(self.bundle, self.digest, "core", image)
                    self.assertEqual(self.digest, receipt["manifestSha256"])
                    self.assertEqual("linux/amd64", receipt["platform"])
                else:
                    with self.assertRaisesRegex(ValueError, "does not contain"):
                        release.verify_image(self.bundle, self.digest, "core", image)
                self.assertEqual(("docker", "rm", "-v", "fixture-container-id"), commands[-1])
                self.assertFalse(any(command[1] in ("run", "start", "pull") for command in commands))


if __name__ == "__main__":
    unittest.main()
