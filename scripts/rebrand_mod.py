from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path


TEXT_SUFFIXES = {
    ".java",
    ".json",
    ".gradle",
    ".properties",
    ".md",
    ".txt",
    ".yml",
    ".yaml",
}
SKIP_DIRS = {".git", ".gradle", "build", "run", "out"}


@dataclass(frozen=True)
class RebrandPlan:
    root: Path
    current_id: str
    current_name: str
    new_id: str
    new_name: str
    current_base_package: str
    new_base_package: str
    current_main_class: str
    new_main_class: str
    current_client_class: str
    new_client_class: str
    current_mixin_file: str
    new_mixin_file: str


def slugify(value: str) -> str:
    slug = re.sub(r"[^a-zA-Z0-9]+", "-", value.strip().lower())
    slug = slug.strip("-")
    if not slug:
        raise ValueError("The mod name must contain letters or numbers.")
    return slug.replace("-", "")


def pascal_case(value: str) -> str:
    words = re.findall(r"[A-Za-z0-9]+", value)
    if not words:
        raise ValueError("The mod name must contain letters or numbers.")
    return "".join(word[:1].upper() + word[1:] for word in words)


def prompt(message: str, default: str | None = None) -> str:
    suffix = f" [{default}]" if default else ""
    raw = input(f"{message}{suffix}: ").strip()
    return raw or (default or "")


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write_text(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8", newline="\n")


def load_plan(root: Path, requested_name: str | None, requested_id: str | None) -> RebrandPlan:
    fabric_mod_path = root / "src" / "main" / "resources" / "fabric.mod.json"
    fabric_mod = json.loads(read_text(fabric_mod_path))

    current_id = fabric_mod["id"]
    current_name = fabric_mod["name"]
    current_main_entry = fabric_mod["entrypoints"]["main"][0]
    current_client_entry = fabric_mod["entrypoints"]["client"][0]
    current_main_package, current_main_class = current_main_entry.rsplit(".", 1)
    current_client_package, current_client_class = current_client_entry.rsplit(".", 1)

    current_base_package = current_main_package
    package_parent = current_base_package.rsplit(".", 1)[0]

    new_name = requested_name or prompt("New mod display name", current_name)
    new_id = requested_id or prompt("New mod id", slugify(new_name))
    new_id = slugify(new_id)
    new_class_prefix = pascal_case(new_name)

    new_base_package = f"{package_parent}.{new_id}"
    new_main_class = f"{new_class_prefix}Mod"
    new_client_class = f"{new_class_prefix}Client"

    return RebrandPlan(
        root=root,
        current_id=current_id,
        current_name=current_name,
        new_id=new_id,
        new_name=new_name,
        current_base_package=current_base_package,
        new_base_package=new_base_package,
        current_main_class=current_main_class,
        new_main_class=new_main_class,
        current_client_class=current_client_class,
        new_client_class=new_client_class,
        current_mixin_file=f"{current_id}.mixins.json",
        new_mixin_file=f"{new_id}.mixins.json",
    )


def build_replacements(plan: RebrandPlan) -> list[tuple[str, str]]:
    current_client_package = f"{plan.current_base_package}.client"
    new_client_package = f"{plan.new_base_package}.client"
    current_mixin_package = f"{plan.current_base_package}.mixin"
    new_mixin_package = f"{plan.new_base_package}.mixin"
    current_main_fqn = f"{plan.current_base_package}.{plan.current_main_class}"
    new_main_fqn = f"{plan.new_base_package}.{plan.new_main_class}"
    current_client_fqn = f"{current_client_package}.{plan.current_client_class}"
    new_client_fqn = f"{new_client_package}.{plan.new_client_class}"

    return [
        (current_main_fqn, new_main_fqn),
        (current_client_fqn, new_client_fqn),
        (current_client_package, new_client_package),
        (current_mixin_package, new_mixin_package),
        (plan.current_base_package, plan.new_base_package),
        (plan.current_main_class, plan.new_main_class),
        (plan.current_client_class, plan.new_client_class),
        (plan.current_mixin_file, plan.new_mixin_file),
        (plan.current_name, plan.new_name),
        (plan.current_id.upper(), plan.new_id.upper()),
        (plan.current_id, plan.new_id),
    ]


def iter_text_files(root: Path) -> list[Path]:
    files: list[Path] = []
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        if path.suffix.lower() in TEXT_SUFFIXES:
            files.append(path)
    return files


def replace_text_content(plan: RebrandPlan) -> list[Path]:
    changed_files: list[Path] = []
    replacements = build_replacements(plan)

    for path in iter_text_files(plan.root):
        original = read_text(path)
        updated = original
        for old, new in replacements:
            updated = updated.replace(old, new)

        if updated != original:
            write_text(path, updated)
            changed_files.append(path)

    return changed_files


def move_path(old_path: Path, new_path: Path) -> bool:
    if not old_path.exists() or old_path == new_path:
        return False
    new_path.parent.mkdir(parents=True, exist_ok=True)
    if new_path.exists():
        raise FileExistsError(f"Refusing to overwrite existing path: {new_path}")
    shutil.move(str(old_path), str(new_path))
    return True


def rename_paths(plan: RebrandPlan) -> list[tuple[Path, Path]]:
    renamed: list[tuple[Path, Path]] = []

    base_package_old_rel = Path(*plan.current_base_package.split("."))
    base_package_new_rel = Path(*plan.new_base_package.split("."))

    path_pairs = [
        (
            plan.root / "src" / "main" / "resources" / plan.current_mixin_file,
            plan.root / "src" / "main" / "resources" / plan.new_mixin_file,
        ),
        (
            plan.root / "src" / "main" / "resources" / "assets" / plan.current_id,
            plan.root / "src" / "main" / "resources" / "assets" / plan.new_id,
        ),
        (
            plan.root / "src" / "main" / "java" / base_package_old_rel,
            plan.root / "src" / "main" / "java" / base_package_new_rel,
        ),
        (
            plan.root / "src" / "client" / "java" / base_package_old_rel,
            plan.root / "src" / "client" / "java" / base_package_new_rel,
        ),
    ]

    for old_path, new_path in path_pairs:
        if move_path(old_path, new_path):
            renamed.append((old_path, new_path))

    renamed.extend(rename_entrypoint_files(plan))
    return renamed


def rename_entrypoint_files(plan: RebrandPlan) -> list[tuple[Path, Path]]:
    renamed: list[tuple[Path, Path]] = []
    main_dir = plan.root / "src" / "main" / "java" / Path(*plan.new_base_package.split("."))
    client_dir = plan.root / "src" / "client" / "java" / Path(*plan.new_base_package.split(".")) / "client"

    entrypoint_pairs = [
        (main_dir / f"{plan.current_main_class}.java", main_dir / f"{plan.new_main_class}.java"),
        (client_dir / f"{plan.current_client_class}.java", client_dir / f"{plan.new_client_class}.java"),
    ]

    for old_path, new_path in entrypoint_pairs:
        if move_path(old_path, new_path):
            renamed.append((old_path, new_path))

    return renamed


def main() -> int:
    parser = argparse.ArgumentParser(description="Rebrand this Fabric mod from the current name/id to a new one.")
    parser.add_argument("name", nargs="?", help="New display name for the mod")
    parser.add_argument("--id", dest="mod_id", help="Override the generated mod id")
    parser.add_argument("--root", default=Path(__file__).resolve().parents[1], type=Path,
                        help="Project root. Defaults to the workspace root.")
    args = parser.parse_args()

    root = args.root.resolve()
    if not (root / "src" / "main" / "resources" / "fabric.mod.json").exists():
        print(f"Could not find fabric.mod.json under {root}", file=sys.stderr)
        return 1

    plan = load_plan(root, args.name, args.mod_id)

    print(f"Current name: {plan.current_name}")
    print(f"Current id:   {plan.current_id}")
    print(f"New name:     {plan.new_name}")
    print(f"New id:       {plan.new_id}")
    print(f"New package:  {plan.new_base_package}")

    confirm = prompt("Apply these changes?", "y").lower()
    if confirm not in {"y", "yes"}:
        print("Cancelled.")
        return 0

    changed_files = replace_text_content(plan)
    renamed_paths = rename_paths(plan)

    print(f"Updated {len(changed_files)} text files.")
    print(f"Renamed {len(renamed_paths)} paths.")
    print("Rebrand complete.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())