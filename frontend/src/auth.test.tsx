import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {MemoryRouter} from 'react-router-dom';
import {afterEach,describe,expect,it,vi} from 'vitest';
import App from './App';
import {hasCapability,hasPermission,type AuthUser} from './auth';

const admin:AuthUser={username:'admin',displayName:'Witness Administrator',roles:['admin'],admin:true,mustChangePassword:false,domainPermissions:{},capabilities:[]};
const restricted:AuthUser={username:'bob',displayName:'Bob Analyst',roles:['retail-analyst'],admin:false,mustChangePassword:false,domainPermissions:{'*':['READ'],retail:['READ','QUERY','WRITE']},capabilities:['VIEW_COMPILED_SQL']};
const model={name:'demo',revision:'12345678',loadedAt:'2026-08-05T00:00:00Z',governanceMode:'local',domains:[],objectCount:0,metricCount:0,relationshipCount:0};
const tokens=(user:AuthUser)=>({accessToken:'access-'+user.username,refreshToken:'browser-must-ignore',expiresIn:3600,tokenType:'Bearer',user});
const response=(body:unknown,status=200)=>({ok:status>=200&&status<300,status,statusText:status===401?'Unauthorized':'Error',json:async()=>body} as Response);

afterEach(()=>{cleanup();vi.restoreAllMocks()});

describe('browser authentication',()=>{
  it('shows an indistinguishable inline login failure',async()=>{
    vi.stubGlobal('fetch',vi.fn(async(input:RequestInfo|URL)=>String(input).endsWith('/auth/refresh')?response({},401):response({message:'internal detail'},401)));
    render(<MemoryRouter><App/></MemoryRouter>);
    fireEvent.change(await screen.findByLabelText('Username'),{target:{value:'unknown'}});
    fireEvent.change(screen.getByLabelText('Password'),{target:{value:'wrong'}});
    fireEvent.click(screen.getByRole('button',{name:'Sign in'}));
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password.');
  });

  it('logs in successfully without sending an API key',async()=>{
    const fetch=vi.fn(async(input:RequestInfo|URL,init?:RequestInit)=>{
      const url=String(input);
      expect(new Headers(init?.headers).has('X-API-Key')).toBe(false);
      if(url.endsWith('/auth/refresh'))return response({},401);
      if(url.endsWith('/auth/login'))return response(tokens(restricted));
      if(url.endsWith('/model'))return response(model);
      return response([]);
    });
    vi.stubGlobal('fetch',fetch);
    render(<MemoryRouter><App/></MemoryRouter>);
    fireEvent.change(await screen.findByLabelText('Username'),{target:{value:'bob'}});
    fireEvent.change(screen.getByLabelText('Password'),{target:{value:'password-123'}});
    fireEvent.click(screen.getByRole('button',{name:'Sign in'}));
    expect((await screen.findAllByText('Bob Analyst')).length).toBeGreaterThan(0);
    expect(fetch).toHaveBeenCalledWith('/api/v1/auth/login',expect.objectContaining({credentials:'same-origin'}));
  });

  it('forces password change and re-establishes the cookie session after success',async()=>{
    const forced={...admin,mustChangePassword:true};
    const fetch=vi.fn(async(input:RequestInfo|URL)=>{
      const url=String(input);
      if(url.endsWith('/auth/refresh'))return response(tokens(forced));
      if(url.endsWith('/auth/password'))return response(undefined,204);
      if(url.endsWith('/auth/login'))return response(tokens(admin));
      if(url.endsWith('/model'))return response(model);
      return response([]);
    });
    vi.stubGlobal('fetch',fetch);
    render(<MemoryRouter><App/></MemoryRouter>);
    expect(await screen.findByText('Secure your account')).toBeInTheDocument();
    expect(screen.queryByText('Semantic model')).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Current password'),{target:{value:'admin'}});
    fireEvent.change(screen.getByLabelText('New password'),{target:{value:'new-admin-password'}});
    fireEvent.change(screen.getByLabelText('Confirm new password'),{target:{value:'new-admin-password'}});
    fireEvent.click(screen.getByRole('button',{name:'Change password'}));
    expect(await screen.findByRole('heading',{name:'Semantic model'})).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith('/api/v1/auth/login',expect.objectContaining({method:'POST'}));
  });

  it('silently restores a session on app load',async()=>{
    const fetch=vi.fn(async(input:RequestInfo|URL)=>String(input).endsWith('/auth/refresh')?response(tokens(restricted)):String(input).endsWith('/model')?response(model):response([]));
    vi.stubGlobal('fetch',fetch);
    render(<MemoryRouter><App/></MemoryRouter>);
    expect((await screen.findAllByText('Bob Analyst')).length).toBeGreaterThan(0);
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/auth/refresh');
  });

  it('drops to login when a request and its refresh retry receive 401',async()=>{
    let refreshes=0;
    vi.stubGlobal('fetch',vi.fn(async(input:RequestInfo|URL)=>{
      const url=String(input);
      if(url.endsWith('/auth/refresh'))return ++refreshes===1?response(tokens(restricted)):response({},401);
      if(url.endsWith('/model')||url.endsWith('/metrics'))return response({},401);
      return response([]);
    }));
    render(<MemoryRouter initialEntries={['/metrics']}><App/></MemoryRouter>);
    expect(await screen.findByText('Welcome back')).toBeInTheDocument();
  });

  it('logs out through the cookie endpoint and returns to login',async()=>{
    const fetch=vi.fn(async(input:RequestInfo|URL)=>String(input).endsWith('/auth/refresh')?response(tokens(restricted)):String(input).endsWith('/auth/logout')?response(undefined,204):String(input).endsWith('/model')?response(model):response([]));
    vi.stubGlobal('fetch',fetch);
    render(<MemoryRouter><App/></MemoryRouter>);
    fireEvent.click(await screen.findByRole('button',{name:'Logout'}));
    expect(await screen.findByText('Welcome back')).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith('/api/v1/auth/logout',expect.objectContaining({method:'POST',credentials:'same-origin'}));
  });
});

describe('authorization hints',()=>{
  it('mirrors SemanticPrincipal wildcard and domain permission precedence',()=>{
    const matrix:[string,'READ'|'QUERY'|'WRITE',boolean][]= [['retail','READ',true],['retail','QUERY',true],['retail','WRITE',true],['ai_rnd','READ',true],['ai_rnd','QUERY',false],['unknown','READ',true],['unknown','WRITE',false]];
    for(const[domain,permission,expected]of matrix)expect(hasPermission(restricted,domain,permission)).toBe(expected);
    expect(hasPermission(admin,'unknown','WRITE')).toBe(true);
    expect(hasCapability(restricted,'VIEW_COMPILED_SQL')).toBe(true);
    expect(hasCapability(restricted,'VIEW_PHYSICAL_LINEAGE')).toBe(false);
    expect(hasCapability(admin,'VIEW_PHYSICAL_LINEAGE')).toBe(true);
  });

  it('gates admin navigation by the real principal',async()=>{
    vi.stubGlobal('fetch',vi.fn(async(input:RequestInfo|URL)=>String(input).endsWith('/auth/refresh')?response(tokens(restricted)):String(input).endsWith('/model')?response(model):response([])));
    const view=render(<MemoryRouter><App/></MemoryRouter>);
    await screen.findAllByText('Bob Analyst');
    expect(screen.queryByRole('link',{name:'Admin'})).not.toBeInTheDocument();
    view.unmount();
    vi.stubGlobal('fetch',vi.fn(async(input:RequestInfo|URL)=>String(input).endsWith('/auth/refresh')?response(tokens(admin)):String(input).endsWith('/model')?response(model):response([])));
    render(<MemoryRouter><App/></MemoryRouter>);
    expect(await screen.findByRole('link',{name:'Admin'})).toBeInTheDocument();
  });

  it('drives user creation from the admin screen',async()=>{
    const requests:{url:string;init?:RequestInit}[]=[];
    const fetch=vi.fn(async(input:RequestInfo|URL,init?:RequestInit)=>{
      const url=String(input);requests.push({url,init});
      if(url.endsWith('/auth/refresh'))return response(tokens(admin));
      if(url.endsWith('/admin/users')&&init?.method==='POST')return response({id:9,username:'alice',provider:'local',displayName:'Alice',enabled:true,mustChangePassword:false,roles:[]},201);
      if(url.endsWith('/admin/users'))return response([]);
      if(url.endsWith('/admin/roles'))return response([{id:3,name:'retail-reader',description:'Retail',admin:false,grants:[{domain:'retail',permissions:['READ']}],capabilities:[]}]);
      if(url.endsWith('/admin/service-accounts'))return response([]);
      return response(undefined,204);
    });
    vi.stubGlobal('fetch',fetch);
    render(<MemoryRouter initialEntries={['/admin']}><App/></MemoryRouter>);
    fireEvent.change(await screen.findByLabelText('Username'),{target:{value:'alice'}});
    fireEvent.change(screen.getByLabelText('Temporary password'),{target:{value:'password-123'}});
    fireEvent.change(screen.getByLabelText('Display name'),{target:{value:'Alice'}});
    fireEvent.click(screen.getByRole('button',{name:'Create user'}));
    await waitFor(()=>expect(requests.some(request=>request.url.endsWith('/admin/users')&&request.init?.method==='POST')).toBe(true));
    const create=requests.find(request=>request.url.endsWith('/admin/users')&&request.init?.method==='POST');
    expect(JSON.parse(String(create?.init?.body))).toMatchObject({username:'alice',displayName:'Alice'});
  });
});
