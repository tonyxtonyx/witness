import {render,screen} from '@testing-library/react';
import {MemoryRouter} from 'react-router-dom';
import {afterEach,describe,expect,it,vi} from 'vitest';
import App from './App';

const object={version:1,kind:'object',metadata:{name:'orders',domain:'retail',label:'Orders',description:'Customer orders',owner:'analytics',tags:['sales']},spec:{source:{catalog:'postgres',schema:'public',table:'orders'},primaryKey:['order_id'],dimensions:[{name:'order_id',type:'bigint',sql:'order_id',nullable:false}],relationships:[]},file:'objects/orders.yaml'};
const model={name:'demo',revision:'1234567890abcdef',loadedAt:'2026-08-05T00:00:00Z',governanceMode:'local',domains:[{key:'retail',label:'Retail',objectCount:1,metricCount:0}],objectCount:1,metricCount:0,relationshipCount:0};

describe('catalog',()=>{
  afterEach(()=>vi.restoreAllMocks());
  it('renders domain-grouped semantic objects from real API shapes',async()=>{
    vi.stubGlobal('fetch',vi.fn(async(input:RequestInfo|URL)=>{
      const url=String(input);
      const body=url.endsWith('/model')?model:url.endsWith('/objects')?[object]:url.endsWith('/metrics')?[]:[];
      return {ok:true,status:200,json:async()=>body} as Response;
    }));
    render(<MemoryRouter><App/></MemoryRouter>);
    expect(await screen.findByText('orders')).toBeInTheDocument();
    expect(screen.getByText('Customer orders')).toBeInTheDocument();
    expect(screen.getByText('1 dims · 0 metrics')).toBeInTheDocument();
    expect(screen.getByText('← postgres.public.orders')).toBeInTheDocument();
  });
});
