import {
  Component,
  RouterOutlet,
  setClassMetadata,
  ɵsetClassDebugInfo,
  ɵɵdefineComponent,
  ɵɵelement
} from "./chunk-Y4NC365O.js";

// src/app/features/projects/project-list.component.ts
var ProjectListComponent = class _ProjectListComponent {
  static \u0275fac = function ProjectListComponent_Factory(__ngFactoryType__) {
    return new (__ngFactoryType__ || _ProjectListComponent)();
  };
  static \u0275cmp = /* @__PURE__ */ \u0275\u0275defineComponent({ type: _ProjectListComponent, selectors: [["app-project-list"]], decls: 1, vars: 0, template: function ProjectListComponent_Template(rf, ctx) {
    if (rf & 1) {
      \u0275\u0275element(0, "router-outlet");
    }
  }, dependencies: [RouterOutlet], encapsulation: 2 });
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && setClassMetadata(ProjectListComponent, [{
    type: Component,
    args: [{
      selector: "app-project-list",
      standalone: true,
      imports: [RouterOutlet],
      template: "<router-outlet />"
    }]
  }], null, null);
})();
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && \u0275setClassDebugInfo(ProjectListComponent, { className: "ProjectListComponent", filePath: "src/app/features/projects/project-list.component.ts", lineNumber: 10 });
})();
export {
  ProjectListComponent
};
//# sourceMappingURL=chunk-HFRWPEMQ.js.map
