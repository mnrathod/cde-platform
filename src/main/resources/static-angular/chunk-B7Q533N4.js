import {
  HttpClient,
  Injectable,
  inject,
  setClassMetadata,
  signal,
  tap,
  ɵɵdefineInjectable
} from "./chunk-Y4NC365O.js";

// src/app/core/services/project.service.ts
var ProjectService = class _ProjectService {
  http = inject(HttpClient);
  projects = signal([], ...ngDevMode ? [{ debugName: "projects" }] : (
    /* istanbul ignore next */
    []
  ));
  selected = signal(null, ...ngDevMode ? [{ debugName: "selected" }] : (
    /* istanbul ignore next */
    []
  ));
  loading = signal(false, ...ngDevMode ? [{ debugName: "loading" }] : (
    /* istanbul ignore next */
    []
  ));
  load() {
    this.loading.set(true);
    return this.http.get("/api/projects").pipe(tap((list) => {
      this.projects.set(list);
      this.loading.set(false);
    }));
  }
  select(project) {
    this.selected.set(project);
  }
  create(data) {
    return this.http.post("/api/projects", data).pipe(tap((p) => this.projects.update((list) => [...list, p])));
  }
  static \u0275fac = function ProjectService_Factory(__ngFactoryType__) {
    return new (__ngFactoryType__ || _ProjectService)();
  };
  static \u0275prov = /* @__PURE__ */ \u0275\u0275defineInjectable({ token: _ProjectService, factory: _ProjectService.\u0275fac, providedIn: "root" });
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && setClassMetadata(ProjectService, [{
    type: Injectable,
    args: [{ providedIn: "root" }]
  }], null, null);
})();

// src/app/core/services/document.service.ts
var DocumentService = class _DocumentService {
  http = inject(HttpClient);
  documents = signal([], ...ngDevMode ? [{ debugName: "documents" }] : (
    /* istanbul ignore next */
    []
  ));
  loading = signal(false, ...ngDevMode ? [{ debugName: "loading" }] : (
    /* istanbul ignore next */
    []
  ));
  loadByProject(projectId) {
    this.loading.set(true);
    return this.http.get(`/api/documents/project/${projectId}`).pipe(tap((docs) => {
      this.documents.set(docs);
      this.loading.set(false);
    }));
  }
  upload(projectId, file, meta) {
    const fd = new FormData();
    fd.append("file", file);
    fd.append("name", meta.name || file.name.replace(/\.[^.]+$/, ""));
    fd.append("documentType", meta.documentType || "OTHER");
    fd.append("drawingNumber", meta.drawingNumber || "");
    fd.append("revision", meta.revision || "");
    fd.append("projectId", String(projectId));
    return this.http.post("/api/documents/upload", fd).pipe(tap((doc) => this.documents.update((list) => [...list, doc])));
  }
  delete(id) {
    return this.http.delete(`/api/documents/${id}`).pipe(tap(() => this.documents.update((list) => list.filter((d) => d.id !== id))));
  }
  getFileIcon(doc) {
    const ext = doc.fileName?.split(".").pop()?.toLowerCase() || "";
    const map = {
      pdf: "\u{1F4D5}",
      doc: "\u{1F4DD}",
      docx: "\u{1F4DD}",
      xls: "\u{1F4CA}",
      xlsx: "\u{1F4CA}",
      ppt: "\u{1F4FD}",
      pptx: "\u{1F4FD}",
      ifc: "\u{1F3D7}",
      rvt: "\u{1F3D7}",
      rfa: "\u{1F3D7}",
      glb: "\u{1F3B2}",
      gltf: "\u{1F3B2}",
      obj: "\u{1F3B2}",
      stl: "\u{1F5A8}",
      dwg: "\u{1F4D0}",
      dxf: "\u{1F4D0}",
      png: "\u{1F5BC}",
      jpg: "\u{1F5BC}",
      svg: "\u{1F3A8}"
    };
    return map[ext] || "\u{1F4C4}";
  }
  is3D(doc) {
    const ext = doc.fileName?.split(".").pop()?.toLowerCase() || "";
    return ["ifc", "glb", "gltf", "obj", "stl", "ply", "dae", "3ds", "rvt", "rfa"].includes(ext);
  }
  static \u0275fac = function DocumentService_Factory(__ngFactoryType__) {
    return new (__ngFactoryType__ || _DocumentService)();
  };
  static \u0275prov = /* @__PURE__ */ \u0275\u0275defineInjectable({ token: _DocumentService, factory: _DocumentService.\u0275fac, providedIn: "root" });
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && setClassMetadata(DocumentService, [{
    type: Injectable,
    args: [{ providedIn: "root" }]
  }], null, null);
})();

export {
  ProjectService,
  DocumentService
};
//# sourceMappingURL=chunk-B7Q533N4.js.map
