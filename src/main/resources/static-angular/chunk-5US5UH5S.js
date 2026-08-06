import {
  HttpClient,
  Injectable,
  inject,
  setClassMetadata,
  ɵɵdefineInjectable
} from "./chunk-Y4NC365O.js";

// src/app/core/services/viewer.service.ts
var ViewerService = class _ViewerService {
  http = inject(HttpClient);
  getViewerData(documentId) {
    return this.http.get(`/api/viewer/${documentId}`);
  }
  get3DData(documentId) {
    return this.http.get(`/api/viewer3d/${documentId}`);
  }
  getAnnotations(documentId) {
    return this.http.get(`/api/annotations/document/${documentId}`);
  }
  saveAnnotation(annotation) {
    return this.http.post("/api/annotations", annotation);
  }
  exportXfdf(documentId) {
    return this.http.get(`/api/annotations/document/${documentId}/xfdf`, {
      responseType: "blob"
    });
  }
  static \u0275fac = function ViewerService_Factory(__ngFactoryType__) {
    return new (__ngFactoryType__ || _ViewerService)();
  };
  static \u0275prov = /* @__PURE__ */ \u0275\u0275defineInjectable({ token: _ViewerService, factory: _ViewerService.\u0275fac, providedIn: "root" });
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && setClassMetadata(ViewerService, [{
    type: Injectable,
    args: [{ providedIn: "root" }]
  }], null, null);
})();

export {
  ViewerService
};
//# sourceMappingURL=chunk-5US5UH5S.js.map
