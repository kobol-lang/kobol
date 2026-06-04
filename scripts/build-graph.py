#!/usr/bin/env python3
"""
build-graph.py — generates a modular code graph under .graph/ for the jcobol project.

Output layout:
  .graph/
    index.json          — manifest: project metadata, stats, module registry
    symbols.json        — flat symbol lookup table (all ids, names, kinds, locations)
    cross-module.json   — edges that cross module boundaries
    modules/
      compiler.json     — nodes + intra-module edges for the compiler module
      runtime.json      — nodes + intra-module edges for the runtime module
      stdlib.json       — nodes + intra-module edges for the stdlib module

Node kinds : file | class | object | interface | enum | annotation |
             sealed_class | sealed_interface | function | property
Edge kinds  : contains | extends | imports | calls
"""

import re
import json
import hashlib
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRAPH_DIR = ROOT / ".graph"
MODULES_DIR = GRAPH_DIR / "modules"

# Regex patterns
RE_PACKAGE     = re.compile(r"^\s*package\s+([\w.]+)", re.M)
RE_IMPORT      = re.compile(r"^\s*import\s+([\w.*]+)", re.M)
RE_CLASS       = re.compile(
    r"^\s*(?:(?:public|private|protected|internal|abstract|open|sealed|data|"
    r"inline|value|annotation|enum)\s+)*"
    r"(class|object|interface|enum class|annotation class|sealed class|sealed interface)\s+"
    r"(\w+)"
    r"(?:\s*<[^>]*>)?"                            # optional type params
    r"(?:\s*\([^)]*\))?"                           # optional primary ctor
    r"(?:\s*:\s*([^\n{]+))?",                      # optional supertype list
    re.M
)
RE_FUN         = re.compile(
    r"^\s*(?:(?:public|private|protected|internal|override|suspend|inline|"
    r"operator|infix|tailrec|external|abstract|open|final|actual|expect)\s+)*"
    r"fun\s+"
    r"(?:<[^>]*>\s*)?"                             # optional type params
    r"(?:([\w.]+)\.)?(\w+)\s*\(",                  # optional receiver & name
    re.M
)
RE_PROP        = re.compile(
    r"^\s*(?:(?:public|private|protected|internal|override|abstract|open|"
    r"lateinit|const|actual|expect)\s+)*"
    r"(?:val|var)\s+(\w+)\s*(?::\s*([\w<>, ?.*]+))?",
    re.M
)
RE_CALL        = re.compile(r"\b(\w+)\s*\(", re.M)

EXCLUDED_DIRS  = {"build", ".gradle", ".git", "node_modules", ".graph"}
SOURCE_ROOTS   = [
    "compiler/src/main/kotlin",
    "compiler/src/test/kotlin",
    "runtime/src/main/kotlin",
    "stdlib/src/main/kotlin",
    "stdlib/src/test/kotlin",
]


def stable_id(*parts: str) -> str:
    """Deterministic short ID from joined parts."""
    raw = ":".join(parts)
    return hashlib.sha1(raw.encode()).hexdigest()[:12]


def module_of(rel: str) -> str:
    if rel.startswith("compiler/"):
        return "compiler"
    if rel.startswith("runtime/"):
        return "runtime"
    if rel.startswith("stdlib/"):
        return "stdlib"
    return "root"


def source_kind(rel: str) -> str:
    return "test" if "/src/test/" in rel else "main"


def collect_kt_files() -> list[Path]:
    files = []
    for root_rel in SOURCE_ROOTS:
        src_root = ROOT / root_rel
        if not src_root.exists():
            continue
        for path in sorted(src_root.rglob("*.kt")):
            files.append(path)
    return files


def parse_file(path: Path, rel: str):
    """Return (file_node, class_nodes, fun_nodes, prop_nodes, edges)."""
    try:
        src = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None, [], [], [], []

    lines = src.splitlines()
    line_map = {}  # offset → line number (1-based)
    off = 0
    for i, ln in enumerate(lines, 1):
        line_map[off] = i
        off += len(ln) + 1

    def offset_to_line(m_start: int) -> int:
        best = 1
        for o, ln in line_map.items():
            if o <= m_start:
                best = ln
            else:
                break
        return best

    pkg_m   = RE_PACKAGE.search(src)
    package = pkg_m.group(1) if pkg_m else ""

    imports = [m.group(1) for m in RE_IMPORT.finditer(src)]

    module = module_of(rel)
    sk     = source_kind(rel)

    file_id   = stable_id(rel)
    file_node = {
        "id":       file_id,
        "kind":     "file",
        "name":     path.name,
        "file":     rel,
        "module":   module,
        "sourceKind": sk,
        "package":  package,
        "line":     1,
    }

    edges        = []
    class_nodes  = []
    fun_nodes    = []
    prop_nodes   = []

    # ── imports → file references ─────────────────────────────────
    for imp in imports:
        edges.append({
            "from": file_id,
            "to":   imp,          # qualified name; resolved later
            "kind": "imports",
        })

    # ── classes / objects / interfaces ────────────────────────────
    class_ids: dict[str, str] = {}   # class name → node id (for contains edges)
    for m in RE_CLASS.finditer(src):
        kind_kw   = m.group(1)   # "class", "object", "interface", …
        cls_name  = m.group(2)
        supers_raw= (m.group(3) or "").strip()
        line      = offset_to_line(m.start())

        qualified = f"{package}.{cls_name}" if package else cls_name
        node_id   = stable_id(rel, cls_name)
        class_ids[cls_name] = node_id

        kind_map = {
            "class": "class",
            "object": "object",
            "interface": "interface",
            "enum class": "enum",
            "annotation class": "annotation",
            "sealed class": "sealed_class",
            "sealed interface": "sealed_interface",
        }

        class_nodes.append({
            "id":            node_id,
            "kind":          kind_map.get(kind_kw, "class"),
            "name":          cls_name,
            "qualifiedName": qualified,
            "file":          rel,
            "module":        module,
            "sourceKind":    sk,
            "package":       package,
            "line":          line,
        })

        # contains edge: file → class
        edges.append({"from": file_id, "to": node_id, "kind": "contains"})

        # extends / implements edges
        if supers_raw:
            # strip generic args & split on commas
            supers = [s.strip().split("<")[0].strip()
                      for s in supers_raw.split(",") if s.strip()]
            for sup in supers:
                if sup:
                    edges.append({
                        "from":  node_id,
                        "to":    sup,       # unqualified; resolved later
                        "kind":  "extends",
                    })

    # ── functions ─────────────────────────────────────────────────
    for m in RE_FUN.finditer(src):
        receiver  = m.group(1) or ""
        fun_name  = m.group(2)
        line      = offset_to_line(m.start())

        qualified = ".".join(filter(None, [package, receiver, fun_name]))
        node_id   = stable_id(rel, receiver, fun_name, str(line))

        fun_nodes.append({
            "id":            node_id,
            "kind":          "function",
            "name":          fun_name,
            "qualifiedName": qualified,
            "receiver":      receiver or None,
            "file":          rel,
            "module":        module,
            "sourceKind":    sk,
            "package":       package,
            "line":          line,
        })

        # contains: closest class or file
        parent_id = file_id
        if receiver and receiver in class_ids:
            parent_id = class_ids[receiver]
        edges.append({"from": parent_id, "to": node_id, "kind": "contains"})

    # ── properties ────────────────────────────────────────────────
    for m in RE_PROP.finditer(src):
        prop_name = m.group(1)
        prop_type = (m.group(2) or "").strip() or None
        line      = offset_to_line(m.start())

        # skip loop variables / local function params by heuristic:
        # only keep properties that start near column 0 (≤8 spaces)
        line_text = lines[line - 1] if line <= len(lines) else ""
        indent    = len(line_text) - len(line_text.lstrip())
        if indent > 8:
            continue

        qualified = f"{package}.{prop_name}" if package else prop_name
        node_id   = stable_id(rel, "prop", prop_name, str(line))

        prop_nodes.append({
            "id":            node_id,
            "kind":          "property",
            "name":          prop_name,
            "qualifiedName": qualified,
            "type":          prop_type,
            "file":          rel,
            "module":        module,
            "sourceKind":    sk,
            "package":       package,
            "line":          line,
        })
        edges.append({"from": file_id, "to": node_id, "kind": "contains"})

    return file_node, class_nodes, fun_nodes, prop_nodes, edges


def resolve_extends_edges(edges, all_nodes):
    """Replace unqualified 'extends' targets with node IDs where possible."""
    name_to_id: dict[str, str] = {}
    for n in all_nodes:
        name_to_id[n["name"]] = n["id"]
        if "qualifiedName" in n:
            name_to_id[n["qualifiedName"]] = n["id"]

    resolved = []
    for e in edges:
        if e["kind"] == "extends":
            target = e["to"]
            node_id = name_to_id.get(target)
            if node_id:
                resolved.append({**e, "to": node_id})
            else:
                # keep as unresolved external ref with a stable id
                resolved.append({
                    **e,
                    "to":       stable_id("ext", target),
                    "toName":   target,
                    "external": True,
                })
        else:
            resolved.append(e)
    return resolved


def build_call_edges(kt_files: list[Path], all_nodes: list[dict]) -> list[dict]:
    """Best-effort: scan function bodies for calls to known function names."""
    fun_by_name: dict[str, list[str]] = {}
    for n in all_nodes:
        if n["kind"] == "function":
            fun_by_name.setdefault(n["name"], []).append(n["id"])

    # also build a file-path → list[fun_node] map for scoping
    file_funs: dict[str, list[dict]] = {}
    for n in all_nodes:
        if n["kind"] == "function":
            file_funs.setdefault(n["file"], []).append(n)

    call_edges = []
    seen = set()
    for path in kt_files:
        try:
            src = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        rel = str(path.relative_to(ROOT))
        callers = file_funs.get(rel, [])
        if not callers:
            continue
        for m in RE_CALL.finditer(src):
            callee_name = m.group(1)
            if callee_name in ("if", "when", "for", "while", "return",
                               "throw", "try", "catch", "finally", "do",
                               "companion", "init", "constructor", "super",
                               "this", "object", "class", "fun", "val", "var"):
                continue
            targets = fun_by_name.get(callee_name, [])
            for caller in callers:
                for tgt in targets:
                    if caller["id"] == tgt:
                        continue
                    key = (caller["id"], tgt)
                    if key not in seen:
                        seen.add(key)
                        call_edges.append({
                            "from": caller["id"],
                            "to":   tgt,
                            "kind": "calls",
                        })
    return call_edges


def build_import_edges(edges, all_nodes) -> list[dict]:
    """Resolve import edges whose 'to' is a qualified name matching a node."""
    qual_to_id: dict[str, str] = {}
    for n in all_nodes:
        if "qualifiedName" in n:
            qual_to_id[n["qualifiedName"]] = n["id"]

    resolved = []
    for e in edges:
        if e["kind"] == "imports":
            target = e["to"]
            node_id = qual_to_id.get(target)
            if node_id:
                resolved.append({**e, "to": node_id})
            # else drop unresolved external imports (stdlib, third-party)
        else:
            resolved.append(e)
    return resolved


def dedupe_edges(edges: list[dict]) -> list[dict]:
    seen = set()
    out  = []
    for e in edges:
        key = (e["from"], e["to"], e["kind"])
        if key not in seen:
            seen.add(key)
            out.append(e)
    return out


# ── Module base packages (used to derive layer names) ───────────────────────
MODULE_BASE_PKG: dict[str, str] = {
    "compiler": "dev.kobol",
    "runtime":  "dev.kobol.runtime",
    "stdlib":   "dev.kobol.stdlib",
}

# Modules with more nodes than this threshold get split into per-layer files
SPLIT_THRESHOLD = 300


def layer_of(node: dict) -> str:
    """Return the full dotted sub-path layer name for a node within its module.

    dev.kobol.parser     → 'parser'
    dev.kobol.parser.ast → 'parser.ast'
    dev.kobol            → 'root'
    """
    mod  = node["module"]
    pkg  = node.get("package", "")
    base = MODULE_BASE_PKG.get(mod, "")
    if base and pkg.startswith(base):
        rest = pkg[len(base):].lstrip(".")  # "parser.ast" or ""
        if rest:
            return rest
    return "root"


def layer_to_relpath(mod: str, la: str, dir_layers: set[str]) -> str:
    """Convert a layer name to a relative .graph/ path string.

    'parser'     (a dir_layer) → 'compiler/parser/core.json'
    'parser.ast'               → 'compiler/parser/ast.json'
    'codegen'                  → 'compiler/codegen.json'
    """
    parts = la.split(".")
    if la in dir_layers:
        # this layer IS a parent — it lives as .../core.json inside its own subdir
        return f".graph/modules/{mod}/{'/'.join(parts)}/core.json"
    elif len(parts) > 1:
        # nested layer: parent-dir/leaf.json
        return f".graph/modules/{mod}/{'/'.join(parts[:-1])}/{parts[-1]}.json"
    else:
        return f".graph/modules/{mod}/{la}.json"


def write_json(path: Path, data) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(data, indent=2, ensure_ascii=False)
    path.write_text(text, encoding="utf-8")
    return path.stat().st_size


def edge_stats(edges: list[dict]) -> dict:
    return {
        "total":    len(edges),
        "calls":    sum(1 for e in edges if e["kind"] == "calls"),
        "contains": sum(1 for e in edges if e["kind"] == "contains"),
        "extends":  sum(1 for e in edges if e["kind"] == "extends"),
        "imports":  sum(1 for e in edges if e["kind"] == "imports"),
    }


def node_stats(nodes: list[dict]) -> dict:
    counts: dict[str, int] = {}
    for n in nodes:
        counts[n["kind"]] = counts.get(n["kind"], 0) + 1
    return counts


def write_layer(
    out_path: Path,
    layer: str,
    module: str,
    nodes: list[dict],
    edges: list[dict],
    generated: str,
    extra_meta: dict | None = None,
) -> int:
    adj: dict[str, list] = {}
    for e in edges:
        adj.setdefault(e["from"], []).append({"to": e["to"], "kind": e["kind"]})
    payload: dict = {
        "layer":     layer,
        "module":    module,
        "generated": generated,
        "stats":     {**node_stats(nodes), "edges": edge_stats(edges)},
        **(extra_meta or {}),
        "nodes":     nodes,
        "edges":     edges,
        "adjacency": adj,
    }
    return write_json(out_path, payload)


def main():
    print(f"[build-graph] root = {ROOT}")
    GRAPH_DIR.mkdir(exist_ok=True)
    MODULES_DIR.mkdir(exist_ok=True)

    kt_files = collect_kt_files()
    print(f"[build-graph] found {len(kt_files)} .kt source files")

    all_file_nodes: list[dict] = []
    all_class_nodes: list[dict] = []
    all_fun_nodes: list[dict] = []
    all_prop_nodes: list[dict] = []
    all_edges: list[dict] = []

    for path in kt_files:
        rel = str(path.relative_to(ROOT))
        fn, cn, fnn, pn, edges = parse_file(path, rel)
        if fn is None:
            continue
        all_file_nodes.append(fn)
        all_class_nodes.extend(cn)
        all_fun_nodes.extend(fnn)
        all_prop_nodes.extend(pn)
        all_edges.extend(edges)

    all_nodes = all_file_nodes + all_class_nodes + all_fun_nodes + all_prop_nodes

    all_edges = resolve_extends_edges(all_edges, all_nodes)
    all_edges = build_import_edges(all_edges, all_nodes)

    print("[build-graph] building call graph …")
    all_edges.extend(build_call_edges(kt_files, all_nodes))
    all_edges = dedupe_edges(all_edges)

    generated = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    # ── partition by module ──────────────────────────────────────────────────
    module_names = sorted({n["module"] for n in all_nodes if n["kind"] == "file"})
    node_module: dict[str, str] = {n["id"]: n["module"] for n in all_nodes}

    mod_nodes: dict[str, list] = {m: [] for m in module_names}
    for n in all_nodes:
        mod_nodes[n["module"]].append(n)

    mod_edges: dict[str, list] = {m: [] for m in module_names}
    cross_mod_edges: list[dict] = []
    for e in all_edges:
        sm = node_module.get(e["from"])
        dm = node_module.get(e["to"])
        if sm and dm and sm == dm:
            mod_edges[sm].append(e)
        else:
            cross_mod_edges.append(e)

    # ── write modules ────────────────────────────────────────────────────────
    module_registry: list[dict] = []

    for mod in module_names:
        nodes = mod_nodes[mod]
        edges = mod_edges[mod]

        if len(nodes) <= SPLIT_THRESHOLD:
            # ── small module: single flat file ──────────────────────────────
            out_path = MODULES_DIR / f"{mod}.json"
            size = write_layer(out_path, mod, mod, nodes, edges, generated)
            kb = size / 1024
            print(f"[build-graph]   .graph/modules/{mod}.json  "
                  f"({len(nodes)} nodes, {len(edges)} edges, {kb:.1f} KB)")
            module_registry.append({
                "name":    mod,
                "split":   False,
                "file":    f".graph/modules/{mod}.json",
                "nodes":   len(nodes),
                "edges":   len(edges),
                "sizeKB":  round(kb, 1),
            })
        else:
            # ── large module: split by layer ─────────────────────────────────

            # assign each node to a full dotted layer name
            node_layer: dict[str, str] = {n["id"]: layer_of(n) for n in nodes}
            layer_names = sorted({v for v in node_layer.values()})

            # layers that are parent prefixes of other layers
            # e.g. "parser" is a parent of "parser.ast" → becomes a subdir
            dir_layers: set[str] = {
                la for la in layer_names
                if any(other != la and other.startswith(la + ".")
                       for other in layer_names)
            }

            # group nodes per layer
            lay_nodes: dict[str, list] = {la: [] for la in layer_names}
            for n in nodes:
                lay_nodes[node_layer[n["id"]]].append(n)

            # classify edges: intra-layer vs cross-layer (within this module)
            lay_edges: dict[str, list] = {la: [] for la in layer_names}
            cross_lay_edges: list[dict] = []
            for e in edges:
                sl = node_layer.get(e["from"])
                dl = node_layer.get(e["to"])
                if sl and dl and sl == dl:
                    lay_edges[sl].append(e)
                else:
                    cross_lay_edges.append(e)

            layers_meta: list[dict] = []
            for la in layer_names:
                ln = lay_nodes[la]
                le = lay_edges[la]
                rel_file = layer_to_relpath(mod, la, dir_layers)
                out_path = ROOT / rel_file
                size = write_layer(
                    out_path, la, mod, ln, le, generated,
                    extra_meta={"description": f"Layer '{la}' of module '{mod}'"},
                )
                kb = size / 1024
                print(f"[build-graph]   {rel_file}  "
                      f"({len(ln)} nodes, {len(le)} edges, {kb:.1f} KB)")
                layers_meta.append({
                    "layer":   la,
                    "file":    rel_file,
                    "nodes":   len(ln),
                    "edges":   len(le),
                    "sizeKB":  round(kb, 1),
                })

            # cross-layer file for this module — sits at the module dir root
            cl_rel  = f".graph/modules/{mod}/cross-layer.json"
            cl_path = ROOT / cl_rel
            annotated_cl = [
                {**e,
                 "fromLayer": node_layer.get(e["from"], "external"),
                 "toLayer":   node_layer.get(e["to"],   "external")}
                for e in cross_lay_edges
            ]
            cl_size = write_json(cl_path, {
                "module":      mod,
                "generated":   generated,
                "description": f"Edges that cross layer boundaries within module '{mod}'",
                "stats":       edge_stats(cross_lay_edges),
                "edges":       annotated_cl,
            })
            print(f"[build-graph]   {cl_rel}  "
                  f"({len(cross_lay_edges)} edges, {cl_size/1024:.1f} KB)")

            module_registry.append({
                "name":       mod,
                "split":      True,
                "dir":        f".graph/modules/{mod}/",
                "crossLayer": cl_rel,
                "layers":     layers_meta,
                "nodes":      len(nodes),
                "edges":      len(edges),
            })

    # ── cross-module.json ────────────────────────────────────────────────────
    annotated_cross = [
        {**e,
         "fromModule": node_module.get(e["from"], "external"),
         "toModule":   node_module.get(e["to"],   "external")}
        for e in cross_mod_edges
    ]
    cross_path = GRAPH_DIR / "cross-module.json"
    cross_size = write_json(cross_path, {
        "generated":   generated,
        "description": "Edges that cross module boundaries",
        "stats":       edge_stats(cross_mod_edges),
        "edges":       annotated_cross,
    })
    print(f"[build-graph]   .graph/cross-module.json  "
          f"({len(cross_mod_edges)} edges, {cross_size/1024:.1f} KB)")

    # ── symbols.json ─────────────────────────────────────────────────────────
    symbols = []
    for n in all_nodes:
        sym: dict = {
            "id":     n["id"],
            "kind":   n["kind"],
            "name":   n["name"],
            "module": n["module"],
            "file":   n["file"],
            "line":   n["line"],
        }
        if "qualifiedName" in n:
            sym["qualifiedName"] = n["qualifiedName"]
        if len(mod_nodes.get(n["module"], [])) > SPLIT_THRESHOLD:
            sym["layer"] = layer_of(n)
        if n.get("sourceKind") == "test":
            sym["test"] = True
        symbols.append(sym)

    sym_path = GRAPH_DIR / "symbols.json"
    sym_size = write_json(sym_path, {
        "generated":   generated,
        "description": "Flat symbol lookup table — load this first for any symbol search",
        "count":       len(symbols),
        "symbols":     symbols,
    })
    print(f"[build-graph]   .graph/symbols.json  "
          f"({len(symbols)} symbols, {sym_size/1024:.1f} KB)")

    # ── index.json ───────────────────────────────────────────────────────────
    total_stats = {
        "files":           len(all_file_nodes),
        "classes":         sum(1 for n in all_nodes if n["kind"] == "class"),
        "objects":         sum(1 for n in all_nodes if n["kind"] == "object"),
        "interfaces":      sum(1 for n in all_nodes if n["kind"] in
                               ("interface", "sealed_interface")),
        "enums":           sum(1 for n in all_nodes if n["kind"] == "enum"),
        "functions":       sum(1 for n in all_nodes if n["kind"] == "function"),
        "properties":      sum(1 for n in all_nodes if n["kind"] == "property"),
        "totalNodes":      len(all_nodes),
        "edges":           edge_stats(all_edges),
        "crossModuleEdges": len(cross_mod_edges),
    }

    index = {
        "version":     "1.2.0",
        "generated":   generated,
        "project":     "jcobol",
        "language":    "Kotlin",
        "description": (
            "Modular code graph for the jcobol compiler. "
            "Start with index.json → use symbols.json for symbol search → "
            "load only the layer/module file you need."
        ),
        "howToUse": {
            "1_search":     "Query symbols.json by name/kind/module/layer",
            "2_navigate":   "Use the symbol's module + layer to pick the right file",
            "3_smallModule":"Load .graph/modules/{module}.json (runtime, stdlib)",
            "4_largeModule":"Load .graph/modules/{module}/{layer}.json (compiler/*)",
            "5_crossLayer": "Load .graph/modules/{module}/cross-layer.json for inter-layer deps",
            "6_crossModule":"Load .graph/cross-module.json for inter-module deps",
        },
        "files": {
            "symbols":     ".graph/symbols.json",
            "crossModule": ".graph/cross-module.json",
        },
        "modules":  module_registry,
        "stats":    total_stats,
    }

    idx_path = GRAPH_DIR / "index.json"
    idx_size = write_json(idx_path, index)
    print(f"[build-graph]   .graph/index.json  ({idx_size/1024:.1f} KB)")
    print(f"[build-graph] done — {total_stats['totalNodes']} nodes, "
          f"{total_stats['edges']['total']} edges")


if __name__ == "__main__":
    main()
