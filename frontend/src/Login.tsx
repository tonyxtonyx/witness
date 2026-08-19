import {useState,type FormEvent,type ReactNode} from 'react';
import {useAuth} from './auth';

export function SessionGate({children}:{children:ReactNode}){
  const{state,user}=useAuth();
  if(state==='loading')return <AuthFrame><div className="auth-loading" role="status"><span className="spinner"/><strong>Restoring your Witness session</strong></div></AuthFrame>;
  if(state==='anonymous'||!user)return <LoginPage/>;
  if(user.mustChangePassword)return <PasswordChange/>;
  return children;
}

export function LoginPage(){
  const{login}=useAuth();
  const[username,setUsername]=useState(''),[password,setPassword]=useState(''),[error,setError]=useState(''),[pending,setPending]=useState(false);
  const submit=async(event:FormEvent)=>{event.preventDefault();setPending(true);setError('');try{await login(username,password)}catch{setError('Invalid username or password.')}finally{setPending(false)}};
  return <AuthFrame><form className="auth-card" onSubmit={submit}><div className="auth-brand"><span>W</span><div><strong>Witness</strong><small>Semantic Layer</small></div></div><div><p className="eyebrow">Sign in</p><h1>Welcome back</h1><p>Use your Witness account to access governed semantic data.</p></div>{error&&<div className="alert error" role="alert"><strong>{error}</strong></div>}<label>Username<input autoFocus autoComplete="username" value={username} onChange={event=>setUsername(event.target.value)}/></label><label>Password<input type="password" autoComplete="current-password" value={password} onChange={event=>setPassword(event.target.value)}/></label><button className="button primary" disabled={pending||!username||!password}>{pending?'Signing in…':'Sign in'}</button></form></AuthFrame>;
}

function PasswordChange(){
  const{changePassword,logout,user}=useAuth();
  const[current,setCurrent]=useState(''),[next,setNext]=useState(''),[confirm,setConfirm]=useState(''),[error,setError]=useState(''),[pending,setPending]=useState(false);
  const submit=async(event:FormEvent)=>{event.preventDefault();if(next!==confirm){setError('New passwords do not match.');return}setPending(true);setError('');try{await changePassword(current,next)}catch(error){setError(error instanceof Error?error.message:'Password change failed.')}finally{setPending(false)}};
  return <AuthFrame><form className="auth-card" onSubmit={submit}><div><p className="eyebrow">Password change required</p><h1>Secure your account</h1><p>{user?.displayName}, replace the default or administrator-reset password before continuing.</p></div>{error&&<div className="alert error" role="alert"><strong>{error}</strong></div>}<label>Current password<input type="password" autoComplete="current-password" value={current} onChange={event=>setCurrent(event.target.value)}/></label><label>New password<input type="password" minLength={8} autoComplete="new-password" value={next} onChange={event=>setNext(event.target.value)}/></label><label>Confirm new password<input type="password" minLength={8} autoComplete="new-password" value={confirm} onChange={event=>setConfirm(event.target.value)}/></label><button className="button primary" disabled={pending||!current||next.length<8||!confirm}>{pending?'Changing password…':'Change password'}</button><button className="text-button" type="button" onClick={()=>void logout()}>Log out</button></form></AuthFrame>;
}

function AuthFrame({children}:{children:ReactNode}){return <main className="auth-screen"><div className="auth-backdrop"/><div className="auth-wrap">{children}</div></main>}
