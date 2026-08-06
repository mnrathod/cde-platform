import {
  AuthService
} from "./chunk-F5EIFQ4X.js";
import {
  DefaultValueAccessor,
  FormsModule,
  NgControlStatus,
  NgControlStatusGroup,
  NgForm,
  NgModel,
  RequiredValidator,
  ɵNgNoValidate
} from "./chunk-RGTUG45E.js";
import {
  CommonModule,
  Component,
  Router,
  inject,
  setClassMetadata,
  signal,
  ɵsetClassDebugInfo,
  ɵɵadvance,
  ɵɵclassMap,
  ɵɵconditional,
  ɵɵconditionalCreate,
  ɵɵdefineComponent,
  ɵɵelementEnd,
  ɵɵelementStart,
  ɵɵgetCurrentView,
  ɵɵlistener,
  ɵɵnextContext,
  ɵɵproperty,
  ɵɵresetView,
  ɵɵrestoreView,
  ɵɵtext,
  ɵɵtextInterpolate1,
  ɵɵtwoWayBindingSet,
  ɵɵtwoWayListener,
  ɵɵtwoWayProperty
} from "./chunk-Y4NC365O.js";

// src/app/features/auth/login.component.ts
function LoginComponent_Conditional_12_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "div", 7);
    \u0275\u0275text(1);
    \u0275\u0275elementEnd();
  }
  if (rf & 2) {
    const ctx_r0 = \u0275\u0275nextContext();
    \u0275\u0275advance();
    \u0275\u0275textInterpolate1(" ", ctx_r0.error(), " ");
  }
}
function LoginComponent_Conditional_13_Template(rf, ctx) {
  if (rf & 1) {
    const _r2 = \u0275\u0275getCurrentView();
    \u0275\u0275elementStart(0, "form", 9);
    \u0275\u0275listener("ngSubmit", function LoginComponent_Conditional_13_Template_form_ngSubmit_0_listener() {
      \u0275\u0275restoreView(_r2);
      const ctx_r0 = \u0275\u0275nextContext();
      return \u0275\u0275resetView(ctx_r0.doLogin());
    });
    \u0275\u0275elementStart(1, "div")(2, "label", 10);
    \u0275\u0275text(3, "Username");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(4, "input", 11);
    \u0275\u0275twoWayListener("ngModelChange", function LoginComponent_Conditional_13_Template_input_ngModelChange_4_listener($event) {
      \u0275\u0275restoreView(_r2);
      const ctx_r0 = \u0275\u0275nextContext();
      \u0275\u0275twoWayBindingSet(ctx_r0.username, $event) || (ctx_r0.username = $event);
      return \u0275\u0275resetView($event);
    });
    \u0275\u0275elementEnd()();
    \u0275\u0275elementStart(5, "div")(6, "label", 10);
    \u0275\u0275text(7, "Password");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(8, "input", 12);
    \u0275\u0275twoWayListener("ngModelChange", function LoginComponent_Conditional_13_Template_input_ngModelChange_8_listener($event) {
      \u0275\u0275restoreView(_r2);
      const ctx_r0 = \u0275\u0275nextContext();
      \u0275\u0275twoWayBindingSet(ctx_r0.password, $event) || (ctx_r0.password = $event);
      return \u0275\u0275resetView($event);
    });
    \u0275\u0275elementEnd()();
    \u0275\u0275elementStart(9, "button", 13);
    \u0275\u0275text(10);
    \u0275\u0275elementEnd()();
    \u0275\u0275elementStart(11, "p", 14);
    \u0275\u0275text(12, "Demo: admin / admin123");
    \u0275\u0275elementEnd();
  }
  if (rf & 2) {
    const ctx_r0 = \u0275\u0275nextContext();
    \u0275\u0275advance(4);
    \u0275\u0275twoWayProperty("ngModel", ctx_r0.username);
    \u0275\u0275advance(4);
    \u0275\u0275twoWayProperty("ngModel", ctx_r0.password);
    \u0275\u0275advance();
    \u0275\u0275property("disabled", ctx_r0.loading());
    \u0275\u0275advance();
    \u0275\u0275textInterpolate1(" ", ctx_r0.loading() ? "Signing in..." : "Sign In", " ");
  }
}
function LoginComponent_Conditional_14_Template(rf, ctx) {
  if (rf & 1) {
    const _r3 = \u0275\u0275getCurrentView();
    \u0275\u0275elementStart(0, "form", 9);
    \u0275\u0275listener("ngSubmit", function LoginComponent_Conditional_14_Template_form_ngSubmit_0_listener() {
      \u0275\u0275restoreView(_r3);
      const ctx_r0 = \u0275\u0275nextContext();
      return \u0275\u0275resetView(ctx_r0.doRegister());
    });
    \u0275\u0275elementStart(1, "div")(2, "label", 10);
    \u0275\u0275text(3, "Username");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(4, "input", 15);
    \u0275\u0275twoWayListener("ngModelChange", function LoginComponent_Conditional_14_Template_input_ngModelChange_4_listener($event) {
      \u0275\u0275restoreView(_r3);
      const ctx_r0 = \u0275\u0275nextContext();
      \u0275\u0275twoWayBindingSet(ctx_r0.username, $event) || (ctx_r0.username = $event);
      return \u0275\u0275resetView($event);
    });
    \u0275\u0275elementEnd()();
    \u0275\u0275elementStart(5, "div")(6, "label", 10);
    \u0275\u0275text(7, "Email");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(8, "input", 16);
    \u0275\u0275twoWayListener("ngModelChange", function LoginComponent_Conditional_14_Template_input_ngModelChange_8_listener($event) {
      \u0275\u0275restoreView(_r3);
      const ctx_r0 = \u0275\u0275nextContext();
      \u0275\u0275twoWayBindingSet(ctx_r0.email, $event) || (ctx_r0.email = $event);
      return \u0275\u0275resetView($event);
    });
    \u0275\u0275elementEnd()();
    \u0275\u0275elementStart(9, "div")(10, "label", 10);
    \u0275\u0275text(11, "Password");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(12, "input", 17);
    \u0275\u0275twoWayListener("ngModelChange", function LoginComponent_Conditional_14_Template_input_ngModelChange_12_listener($event) {
      \u0275\u0275restoreView(_r3);
      const ctx_r0 = \u0275\u0275nextContext();
      \u0275\u0275twoWayBindingSet(ctx_r0.password, $event) || (ctx_r0.password = $event);
      return \u0275\u0275resetView($event);
    });
    \u0275\u0275elementEnd()();
    \u0275\u0275elementStart(13, "button", 18);
    \u0275\u0275text(14);
    \u0275\u0275elementEnd()();
  }
  if (rf & 2) {
    const ctx_r0 = \u0275\u0275nextContext();
    \u0275\u0275advance(4);
    \u0275\u0275twoWayProperty("ngModel", ctx_r0.username);
    \u0275\u0275advance(4);
    \u0275\u0275twoWayProperty("ngModel", ctx_r0.email);
    \u0275\u0275advance(4);
    \u0275\u0275twoWayProperty("ngModel", ctx_r0.password);
    \u0275\u0275advance();
    \u0275\u0275property("disabled", ctx_r0.loading());
    \u0275\u0275advance();
    \u0275\u0275textInterpolate1(" ", ctx_r0.loading() ? "Creating..." : "Create Account", " ");
  }
}
var LoginComponent = class _LoginComponent {
  auth = inject(AuthService);
  router = inject(Router);
  tab = signal("login", ...ngDevMode ? [{ debugName: "tab" }] : (
    /* istanbul ignore next */
    []
  ));
  loading = signal(false, ...ngDevMode ? [{ debugName: "loading" }] : (
    /* istanbul ignore next */
    []
  ));
  error = signal("", ...ngDevMode ? [{ debugName: "error" }] : (
    /* istanbul ignore next */
    []
  ));
  username = "";
  password = "";
  email = "";
  doLogin() {
    if (!this.username || !this.password)
      return;
    this.loading.set(true);
    this.error.set("");
    this.auth.login({ username: this.username, password: this.password }).subscribe({
      next: () => this.router.navigate(["/"]),
      error: () => {
        this.error.set("Invalid username or password");
        this.loading.set(false);
      }
    });
  }
  doRegister() {
    this.error.set("Registration coming soon. Use demo: admin / admin123");
  }
  static \u0275fac = function LoginComponent_Factory(__ngFactoryType__) {
    return new (__ngFactoryType__ || _LoginComponent)();
  };
  static \u0275cmp = /* @__PURE__ */ \u0275\u0275defineComponent({ type: _LoginComponent, selectors: [["app-login"]], decls: 15, vars: 7, consts: [[1, "min-h-screen", "bg-gradient-to-br", "from-nav", "to-accent", "flex", "items-center", "justify-center", "p-4"], [1, "bg-white", "rounded-lg", "shadow-2xl", "p-8", "w-full", "max-w-sm"], [1, "flex", "items-center", "gap-3", "mb-8"], [1, "w-9", "h-9", "bg-accent", "rounded", "flex", "items-center", "justify-center", "text-white", "font-black", "text-sm"], [1, "font-bold", "text-lg", "text-gray-800"], [1, "flex", "gap-1", "mb-6", "bg-gray-100", "p-1", "rounded"], [1, "flex-1", "py-1.5", "text-sm", "rounded", "transition-all", 3, "click"], [1, "mb-4", "p-3", "bg-red-50", "border", "border-red-200", "text-red-700", "text-sm", "rounded"], [1, "space-y-4"], [1, "space-y-4", 3, "ngSubmit"], [1, "block", "text-xs", "font-medium", "text-gray-600", "mb-1"], ["name", "username", "type", "text", "required", "", "placeholder", "admin", 1, "w-full", "px-3", "py-2", "border", "border-gray-300", "rounded", "text-sm", "focus:outline-none", "focus:ring-2", "focus:ring-accent", "focus:border-transparent", 3, "ngModelChange", "ngModel"], ["name", "password", "type", "password", "required", "", "placeholder", "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022", 1, "w-full", "px-3", "py-2", "border", "border-gray-300", "rounded", "text-sm", "focus:outline-none", "focus:ring-2", "focus:ring-accent", "focus:border-transparent", 3, "ngModelChange", "ngModel"], ["type", "submit", 1, "w-full", "bg-accent", "hover:bg-blue-700", "disabled:opacity-50", "text-white", "font-semibold", "py-2.5", "rounded", "text-sm", "transition-colors", "mt-2", 3, "disabled"], [1, "text-xs", "text-gray-400", "text-center", "mt-4"], ["name", "username", "type", "text", "required", "", 1, "w-full", "px-3", "py-2", "border", "border-gray-300", "rounded", "text-sm", "focus:outline-none", "focus:ring-2", "focus:ring-accent", 3, "ngModelChange", "ngModel"], ["name", "email", "type", "email", 1, "w-full", "px-3", "py-2", "border", "border-gray-300", "rounded", "text-sm", "focus:outline-none", "focus:ring-2", "focus:ring-accent", 3, "ngModelChange", "ngModel"], ["name", "password", "type", "password", "required", "", 1, "w-full", "px-3", "py-2", "border", "border-gray-300", "rounded", "text-sm", "focus:outline-none", "focus:ring-2", "focus:ring-accent", 3, "ngModelChange", "ngModel"], ["type", "submit", 1, "w-full", "bg-accent", "hover:bg-blue-700", "disabled:opacity-50", "text-white", "font-semibold", "py-2.5", "rounded", "text-sm", "transition-colors", 3, "disabled"]], template: function LoginComponent_Template(rf, ctx) {
    if (rf & 1) {
      \u0275\u0275elementStart(0, "div", 0)(1, "div", 1)(2, "div", 2)(3, "div", 3);
      \u0275\u0275text(4, "CDE");
      \u0275\u0275elementEnd();
      \u0275\u0275elementStart(5, "span", 4);
      \u0275\u0275text(6, "Platform");
      \u0275\u0275elementEnd()();
      \u0275\u0275elementStart(7, "div", 5)(8, "button", 6);
      \u0275\u0275listener("click", function LoginComponent_Template_button_click_8_listener() {
        return ctx.tab.set("login");
      });
      \u0275\u0275text(9, "Sign In");
      \u0275\u0275elementEnd();
      \u0275\u0275elementStart(10, "button", 6);
      \u0275\u0275listener("click", function LoginComponent_Template_button_click_10_listener() {
        return ctx.tab.set("register");
      });
      \u0275\u0275text(11, "Register");
      \u0275\u0275elementEnd()();
      \u0275\u0275conditionalCreate(12, LoginComponent_Conditional_12_Template, 2, 1, "div", 7);
      \u0275\u0275conditionalCreate(13, LoginComponent_Conditional_13_Template, 13, 4);
      \u0275\u0275conditionalCreate(14, LoginComponent_Conditional_14_Template, 15, 5, "form", 8);
      \u0275\u0275elementEnd()();
    }
    if (rf & 2) {
      \u0275\u0275advance(8);
      \u0275\u0275classMap(ctx.tab() === "login" ? "bg-white text-accent shadow-sm font-semibold" : "text-gray-500");
      \u0275\u0275advance(2);
      \u0275\u0275classMap(ctx.tab() === "register" ? "bg-white text-accent shadow-sm font-semibold" : "text-gray-500");
      \u0275\u0275advance(2);
      \u0275\u0275conditional(ctx.error() ? 12 : -1);
      \u0275\u0275advance();
      \u0275\u0275conditional(ctx.tab() === "login" ? 13 : -1);
      \u0275\u0275advance();
      \u0275\u0275conditional(ctx.tab() === "register" ? 14 : -1);
    }
  }, dependencies: [FormsModule, \u0275NgNoValidate, DefaultValueAccessor, NgControlStatus, NgControlStatusGroup, RequiredValidator, NgModel, NgForm, CommonModule], encapsulation: 2 });
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && setClassMetadata(LoginComponent, [{
    type: Component,
    args: [{
      selector: "app-login",
      standalone: true,
      imports: [FormsModule, CommonModule],
      template: `
    <div class="min-h-screen bg-gradient-to-br from-nav to-accent flex items-center justify-center p-4">
      <div class="bg-white rounded-lg shadow-2xl p-8 w-full max-w-sm">

        <!-- Logo -->
        <div class="flex items-center gap-3 mb-8">
          <div class="w-9 h-9 bg-accent rounded flex items-center justify-center text-white font-black text-sm">CDE</div>
          <span class="font-bold text-lg text-gray-800">Platform</span>
        </div>

        <!-- Tabs -->
        <div class="flex gap-1 mb-6 bg-gray-100 p-1 rounded">
          <button (click)="tab.set('login')"
            class="flex-1 py-1.5 text-sm rounded transition-all"
            [class]="tab() === 'login' ? 'bg-white text-accent shadow-sm font-semibold' : 'text-gray-500'"
          >Sign In</button>
          <button (click)="tab.set('register')"
            class="flex-1 py-1.5 text-sm rounded transition-all"
            [class]="tab() === 'register' ? 'bg-white text-accent shadow-sm font-semibold' : 'text-gray-500'"
          >Register</button>
        </div>

        <!-- Error -->
        @if (error()) {
          <div class="mb-4 p-3 bg-red-50 border border-red-200 text-red-700 text-sm rounded">
            {{ error() }}
          </div>
        }

        <!-- Login Form -->
        @if (tab() === 'login') {
          <form (ngSubmit)="doLogin()" class="space-y-4">
            <div>
              <label class="block text-xs font-medium text-gray-600 mb-1">Username</label>
              <input [(ngModel)]="username" name="username" type="text" required
                class="w-full px-3 py-2 border border-gray-300 rounded text-sm focus:outline-none focus:ring-2 focus:ring-accent focus:border-transparent"
                placeholder="admin" />
            </div>
            <div>
              <label class="block text-xs font-medium text-gray-600 mb-1">Password</label>
              <input [(ngModel)]="password" name="password" type="password" required
                class="w-full px-3 py-2 border border-gray-300 rounded text-sm focus:outline-none focus:ring-2 focus:ring-accent focus:border-transparent"
                placeholder="\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" />
            </div>
            <button type="submit" [disabled]="loading()"
              class="w-full bg-accent hover:bg-blue-700 disabled:opacity-50 text-white font-semibold py-2.5 rounded text-sm transition-colors mt-2">
              {{ loading() ? 'Signing in...' : 'Sign In' }}
            </button>
          </form>
          <p class="text-xs text-gray-400 text-center mt-4">Demo: admin / admin123</p>
        }

        <!-- Register Form -->
        @if (tab() === 'register') {
          <form (ngSubmit)="doRegister()" class="space-y-4">
            <div>
              <label class="block text-xs font-medium text-gray-600 mb-1">Username</label>
              <input [(ngModel)]="username" name="username" type="text" required
                class="w-full px-3 py-2 border border-gray-300 rounded text-sm focus:outline-none focus:ring-2 focus:ring-accent" />
            </div>
            <div>
              <label class="block text-xs font-medium text-gray-600 mb-1">Email</label>
              <input [(ngModel)]="email" name="email" type="email"
                class="w-full px-3 py-2 border border-gray-300 rounded text-sm focus:outline-none focus:ring-2 focus:ring-accent" />
            </div>
            <div>
              <label class="block text-xs font-medium text-gray-600 mb-1">Password</label>
              <input [(ngModel)]="password" name="password" type="password" required
                class="w-full px-3 py-2 border border-gray-300 rounded text-sm focus:outline-none focus:ring-2 focus:ring-accent" />
            </div>
            <button type="submit" [disabled]="loading()"
              class="w-full bg-accent hover:bg-blue-700 disabled:opacity-50 text-white font-semibold py-2.5 rounded text-sm transition-colors">
              {{ loading() ? 'Creating...' : 'Create Account' }}
            </button>
          </form>
        }
      </div>
    </div>
  `
    }]
  }], null, null);
})();
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && \u0275setClassDebugInfo(LoginComponent, { className: "LoginComponent", filePath: "src/app/features/auth/login.component.ts", lineNumber: 91 });
})();
export {
  LoginComponent
};
//# sourceMappingURL=chunk-PSXL2ZTM.js.map
