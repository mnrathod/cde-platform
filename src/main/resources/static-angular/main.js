import {
  AuthService
} from "./chunk-F5EIFQ4X.js";
import {
  Component,
  Router,
  RouterOutlet,
  bootstrapApplication,
  catchError,
  inject,
  provideHttpClient,
  provideRouter,
  provideZoneChangeDetection,
  setClassMetadata,
  throwError,
  withInterceptors,
  ɵsetClassDebugInfo,
  ɵɵdefineComponent,
  ɵɵelement
} from "./chunk-Y4NC365O.js";

// src/app/core/guards/auth.guard.ts
var authGuard = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.isLoggedIn())
    return true;
  router.navigate(["/login"]);
  return false;
};

// src/app/app.routes.ts
var routes = [
  {
    path: "login",
    loadComponent: () => import("./chunk-PSXL2ZTM.js").then((m) => m.LoginComponent)
  },
  {
    path: "",
    canActivate: [authGuard],
    loadComponent: () => import("./chunk-JC55P7LT.js").then((m) => m.ShellComponent),
    children: [
      {
        path: "projects",
        loadComponent: () => import("./chunk-HFRWPEMQ.js").then((m) => m.ProjectListComponent)
      },
      { path: "", redirectTo: "projects", pathMatch: "full" }
    ]
  },
  {
    path: "viewer/:id",
    canActivate: [authGuard],
    loadComponent: () => import("./chunk-O72TJYGT.js").then((m) => m.ViewerComponent)
  },
  {
    path: "viewer3d/:id",
    canActivate: [authGuard],
    loadComponent: () => import("./chunk-HXKZ554R.js").then((m) => m.Viewer3dComponent)
  },
  {
    path: "compare",
    canActivate: [authGuard],
    loadComponent: () => import("./chunk-4VNFMOHA.js").then((m) => m.CompareComponent)
  },
  { path: "**", redirectTo: "" }
];

// src/app/core/interceptors/auth.interceptor.ts
var authInterceptor = (req, next) => {
  const auth = inject(AuthService);
  const token = auth.token();
  const authReq = token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
  return next(authReq).pipe(catchError((err) => {
    if (err.status === 401)
      auth.logout();
    return throwError(() => err);
  }));
};

// src/app/app.config.ts
var appConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor]))
  ]
};

// src/app/app.ts
var App = class _App {
  static \u0275fac = function App_Factory(__ngFactoryType__) {
    return new (__ngFactoryType__ || _App)();
  };
  static \u0275cmp = /* @__PURE__ */ \u0275\u0275defineComponent({ type: _App, selectors: [["app-root"]], decls: 1, vars: 0, template: function App_Template(rf, ctx) {
    if (rf & 1) {
      \u0275\u0275element(0, "router-outlet");
    }
  }, dependencies: [RouterOutlet], encapsulation: 2 });
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && setClassMetadata(App, [{
    type: Component,
    args: [{ selector: "app-root", standalone: true, imports: [RouterOutlet], template: "<router-outlet />\n" }]
  }], null, null);
})();
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && \u0275setClassDebugInfo(App, { className: "App", filePath: "src/app/app.ts", lineNumber: 10 });
})();

// src/main.ts
bootstrapApplication(App, appConfig).catch((err) => console.error(err));
//# sourceMappingURL=main.js.map
