export type Metadata={name:string;domain:string;label?:string;description?:string;owner?:string;tags:string[]};
export type Dimension={name:string;label?:string;description?:string;type:string;sql:string;nullable?:boolean};
export type Relationship={name:string;targetObject:string;sourceFields:string[];targetFields:string[];cardinality:string;defaultJoinType:string};
export type ObjectSourceDefinition={catalog?:string;schema?:string;table?:string;select?:string};
export type SemanticObject={version:number;kind:string;metadata:Metadata;spec:{source:ObjectSourceDefinition;primaryKey:string[];dimensions:Dimension[];relationships:Relationship[]};file:string};
export type MetricFilter={field:string;operator:string;values:unknown[]};
export type Metric={version:number;kind:string;metadata:Metadata;spec:{baseObject:string;aggregation:string;expression:string;resultType:string;format:string;filters:MetricFilter[]};file:string};
export type Domain={key:string;label:string;objectCount:number;metricCount:number};
export type ModelSummary={name:string;revision:string;loadedAt:string;governanceMode:'local'|'governed';domains:Domain[];objectCount:number;metricCount:number;relationshipCount:number};
export type ObjectSource={file:string;yaml?:string;physicalSource?:string;fields:{dimension:string;expression:string}[]};
export type QueryColumn={name:string;jdbcType:number;typeName:string;nullable:boolean};
export type QueryResponse={columns:QueryColumn[];rows:unknown[][];rowCount:number;elapsedMs:number;compiledTrinoSql?:string;queryId?:string};
export type SearchResult={type:'object'|'dimension'|'metric';name:string;label?:string;domain:string;path:string;verified:boolean;owner?:string;snippet?:string};
export type ValidationResponse={valid:boolean;errors:{field:string;code:string;message:string;severity:string}[]};
export type ChangePreview={baseRevision:string;validation:{valid:boolean;errors:{file:string;path:string;code:string;message:string;severity:string}[]};diff:string;affectedObjects:string[];affectedMetrics:string[]};
export type ChangeResult={branch:string;commitSha:string;mergeRequest:{id:number;url:string}};

let accessToken:string|null=null;
let refreshSession:(()=>Promise<boolean>)|null=null;
let sessionExpired:(()=>void)|null=null;

export class ApiError extends Error{
  status:number;
  code?:string;
  hint?:string;
  constructor(status:number,message:string,code?:string,hint?:string){super(message);this.name='ApiError';this.status=status;this.code=code;this.hint=hint}
}

export function setApiAccessToken(token:string|null){accessToken=token}
export function configureApiSession(refresh:()=>Promise<boolean>,expired:()=>void){refreshSession=refresh;sessionExpired=expired}

export async function api<T>(path:string,init?:RequestInit):Promise<T>{return request<T>(path,init,true)}

export async function authApi<T>(path:string,init?:RequestInit):Promise<T>{
  const headers=new Headers(init?.headers);
  if(init?.body&&!headers.has('Content-Type'))headers.set('Content-Type','application/json');
  const res=await fetch('/api/v1/auth'+path,{...init,credentials:'same-origin',headers});
  if(!res.ok){
    const body=await res.json().catch(()=>({message:res.statusText}));
    throw new ApiError(res.status,body.message||res.statusText,body.code,body.hint);
  }
  if(res.status===204)return undefined as T;
  return res.json();
}

async function request<T>(path:string,init:RequestInit|undefined,retry:boolean):Promise<T>{
  const headers=new Headers(init?.headers);
  if(accessToken)headers.set('Authorization','Bearer '+accessToken);
  if(init?.body&&!headers.has('Content-Type'))headers.set('Content-Type','application/json');
  const res=await fetch('/api/v1'+path,{...init,credentials:'same-origin',headers});
  if(res.status===401&&retry&&refreshSession&&!path.startsWith('/auth/')){
    if(await refreshSession())return request<T>(path,init,false);
  }
  if(!res.ok){
    const body=await res.json().catch(()=>({message:res.statusText}));
    if(res.status===401&&sessionExpired){
      sessionExpired();
      // The authenticated tree is being unmounted. Abandon the caller instead of
      // surfacing an unhandled rejection from a page-level data-loading effect.
      return new Promise<T>(()=>{});
    }
    const message=res.status===403?'You are not permitted to perform this action.':body.message||body.hint||res.statusText;
    throw new ApiError(res.status,message,body.code,body.hint);
  }
  if(res.status===204)return undefined as T;
  return res.json();
}
