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

const key=import.meta.env.VITE_API_KEY;
export async function api<T>(path:string,init?:RequestInit):Promise<T>{
  const res=await fetch('/api/v1'+path,{...init,headers:{...(key?{'X-API-Key':key}:{}),'Content-Type':'application/json',...init?.headers}});
  if(!res.ok){const body=await res.json().catch(()=>({message:res.statusText}));const error=new Error(body.message||body.hint||res.statusText) as Error&{code?:string;hint?:string};error.code=body.code;error.hint=body.hint;throw error}
  if(res.status===204)return undefined as T;
  return res.json();
}
