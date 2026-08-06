import {
  HttpClient,
  Injectable,
  Router,
  computed,
  inject,
  setClassMetadata,
  signal,
  tap,
  ɵɵdefineInjectable
} from "./chunk-Y4NC365O.js";

// src/app/core/services/auth.service.ts
var TOKEN_KEY = "cde_token";
var AuthService = class _AuthService {
  http = inject(HttpClient);
  router = inject(Router);
  // ── Signals ──────────────────────────────────────────────────
  _token = signal(localStorage.getItem(TOKEN_KEY), ...ngDevMode ? [{ debugName: "_token" }] : (
    /* istanbul ignore next */
    []
  ));
  _username = signal(null, ...ngDevMode ? [{ debugName: "_username" }] : (
    /* istanbul ignore next */
    []
  ));
  _role = signal(null, ...ngDevMode ? [{ debugName: "_role" }] : (
    /* istanbul ignore next */
    []
  ));
  token = this._token.asReadonly();
  username = this._username.asReadonly();
  role = this._role.asReadonly();
  isLoggedIn = computed(() => !!this._token(), ...ngDevMode ? [{ debugName: "isLoggedIn" }] : (
    /* istanbul ignore next */
    []
  ));
  constructor() {
    const t = this._token();
    if (t)
      this.parseToken(t);
  }
  login(req) {
    return this.http.post("/api/auth/login", req).pipe(tap((res) => {
      localStorage.setItem(TOKEN_KEY, res.token);
      this._token.set(res.token);
      this._username.set(res.username);
      this._role.set(res.role);
    }));
  }
  logout() {
    localStorage.removeItem(TOKEN_KEY);
    this._token.set(null);
    this._username.set(null);
    this._role.set(null);
    this.router.navigate(["/login"]);
  }
  getAuthHeaders() {
    const t = this._token();
    return t ? { Authorization: `Bearer ${t}` } : {};
  }
  parseToken(token) {
    try {
      const payload = JSON.parse(atob(token.split(".")[1]));
      this._username.set(payload.sub || null);
    } catch {
    }
  }
  static \u0275fac = function AuthService_Factory(__ngFactoryType__) {
    return new (__ngFactoryType__ || _AuthService)();
  };
  static \u0275prov = /* @__PURE__ */ \u0275\u0275defineInjectable({ token: _AuthService, factory: _AuthService.\u0275fac, providedIn: "root" });
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && setClassMetadata(AuthService, [{
    type: Injectable,
    args: [{ providedIn: "root" }]
  }], () => [], null);
})();

export {
  AuthService
};
//# sourceMappingURL=chunk-F5EIFQ4X.js.map
