import {createContext,useCallback,useContext,useEffect,useMemo,useRef,useState,type ReactNode} from 'react';
import {api,authApi,configureApiSession,setApiAccessToken} from './api';

export type Permission='READ'|'QUERY'|'WRITE';
export type Capability='VIEW_COMPILED_SQL'|'VIEW_PHYSICAL_LINEAGE';
export type AuthUser={username:string;displayName:string;roles:string[];admin:boolean;mustChangePassword:boolean;domainPermissions:Record<string,Permission[]>;capabilities:Capability[]};
type TokenResponse={accessToken:string;expiresIn:number;tokenType:string;user:AuthUser};
type AuthState='loading'|'authenticated'|'anonymous';
type AuthContextValue={state:AuthState;user:AuthUser|null;login:(username:string,password:string)=>Promise<void>;logout:()=>Promise<void>;changePassword:(currentPassword:string,newPassword:string)=>Promise<void>};

const AuthContext=createContext<AuthContextValue|null>(null);

// UI permission checks are hints only. The server remains the sole authorization enforcement point.
// Keep this identical to SemanticPrincipal.hasPermission: admin, wildcard, then domain-specific grant.
export function hasPermission(user:AuthUser|null,domain:string,permission:Permission){
  if(!user)return false;
  if(user.admin)return true;
  return Boolean(user.domainPermissions['*']?.includes(permission)||user.domainPermissions[domain]?.includes(permission));
}

export function hasCapability(user:AuthUser|null,capability:Capability){return Boolean(user&&(user.admin||user.capabilities.includes(capability)))}
export function canWriteAny(user:AuthUser|null){return Boolean(user&&(user.admin||Object.values(user.domainPermissions).some(grants=>grants.includes('WRITE'))))}

export function AuthProvider({children}:{children:ReactNode}){
  const[state,setState]=useState<AuthState>('loading');
  const[user,setUser]=useState<AuthUser|null>(null);
  const timer=useRef<number>();
  const refreshInFlight=useRef<Promise<boolean>|null>(null);

  const clear=useCallback(()=>{
    if(timer.current)window.clearTimeout(timer.current);
    timer.current=undefined;
    setApiAccessToken(null);
    setUser(null);
    setState('anonymous');
  },[]);

  const apply=useCallback((tokens:TokenResponse)=>{
    setApiAccessToken(tokens.accessToken);
    setUser(tokens.user);
    setState('authenticated');
    if(timer.current)window.clearTimeout(timer.current);
    const delay=Math.max(10_000,(tokens.expiresIn-60)*1000);
    timer.current=window.setTimeout(()=>void refresh(),delay);
  // refresh is stable after initial render and invoked only by the scheduled callback.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  },[]);

  const performRefresh=useCallback(async()=>{
    try{
      apply(await authApi<TokenResponse>('/refresh',{method:'POST'}));
      return true;
    }catch{clear();return false}
  },[apply,clear]);

  const refresh=useCallback(()=>{
    if(!refreshInFlight.current)refreshInFlight.current=performRefresh().finally(()=>{refreshInFlight.current=null});
    return refreshInFlight.current;
  },[performRefresh]);

  useEffect(()=>{
    configureApiSession(refresh,clear);
    void refresh();
    return()=>{if(timer.current)window.clearTimeout(timer.current);setApiAccessToken(null)};
  },[clear,refresh]);

  const login=useCallback(async(username:string,password:string)=>{
    try{apply(await authApi<TokenResponse>('/login',{method:'POST',body:JSON.stringify({username,password})}))}
    catch{throw new Error('Invalid username or password.')}
  },[apply]);

  const logout=useCallback(async()=>{
    try{await authApi('/logout',{method:'POST'})}finally{clear()}
  },[clear]);

  const changePassword=useCallback(async(currentPassword:string,newPassword:string)=>{
    if(!user)throw new Error('No authenticated user.');
    await api('/auth/password',{method:'POST',body:JSON.stringify({currentPassword,newPassword})});
    await login(user.username,newPassword);
  },[login,user]);

  const value=useMemo(()=>({state,user,login,logout,changePassword}),[state,user,login,logout,changePassword]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(){const value=useContext(AuthContext);if(!value)throw new Error('useAuth must be used inside AuthProvider');return value}
