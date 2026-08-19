import {cleanup,fireEvent,render,screen} from '@testing-library/react';
import {MemoryRouter} from 'react-router-dom';
import {afterEach,describe,expect,it,vi} from 'vitest';
import App from './App';

const object={version:1,kind:'object',metadata:{name:'orders',domain:'retail',label:'Orders',description:'Customer orders',owner:'analytics',tags:['sales']},spec:{source:{catalog:'postgres',schema:'public',table:'orders'},primaryKey:['order_id'],dimensions:[{name:'order_id',type:'bigint',sql:'order_id',nullable:false}],relationships:[]},file:'objects/orders.yaml'};
const model={name:'demo',revision:'1234567890abcdef',loadedAt:'2026-08-05T00:00:00Z',governanceMode:'local',domains:[{key:'retail',label:'Retail',objectCount:1,metricCount:0}],objectCount:1,metricCount:0,relationshipCount:0};

afterEach(()=>cleanup());

describe('catalog',()=>{
  afterEach(()=>vi.restoreAllMocks());
  it('renders domain-grouped semantic objects from real API shapes',async()=>{
    vi.stubGlobal('fetch',vi.fn(async(input:RequestInfo|URL,init?:RequestInit)=>{
      expect(new Headers(init?.headers).has('X-API-Key')).toBe(false);
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

describe('object builder',()=>{
  afterEach(()=>{
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it('generates YAML visually and stages a domain move for GitLab review',async()=>{
    const governedModel={
      ...model,
      governanceMode:'governed',
      domains:[
        {key:'retail',label:'Retail',objectCount:1,metricCount:0},
        {key:'ai_rnd',label:'AI R&D',objectCount:0,metricCount:0}
      ]
    };
    const source={
      file:'objects/orders.yaml',
      yaml:'kind: object',
      physicalSource:'postgres.public.orders',
      fields:[]
    };
    vi.stubGlobal('fetch',vi.fn(async(input:RequestInfo|URL)=>{
      const url=String(input);
      const body=url.endsWith('/model')?governedModel
        :url.endsWith('/objects/retail.orders/source')?source
        :url.endsWith('/objects/retail.orders')?object
        :url.endsWith('/objects')?[object]
        :url.endsWith('/metrics')?[]
        :[];
      return {ok:true,status:200,json:async()=>body} as Response;
    }));

    render(
      <MemoryRouter initialEntries={['/objects/retail.orders/edit']}>
        <App/>
      </MemoryRouter>
    );

    expect(await screen.findByText('Generated object YAML')).toBeInTheDocument();
    expect(screen.getByDisplayValue('Orders')).toBeInTheDocument();
    expect(screen.getByText(/name: orders/)).toBeInTheDocument();

    fireEvent.change(screen.getAllByDisplayValue('order_id')[0],{target:{value:'purchase_id'}});
    const yamlPreview=screen.getByText('Generated object YAML').closest('section')?.querySelector('pre');
    expect(yamlPreview?.textContent).toContain('primaryKey:\n    - purchase_id');

    fireEvent.click(screen.getByRole('button',{name:'SQL select'}));
    fireEvent.change(screen.getByLabelText('Source SELECT'),{target:{value:'SELECT o.order_id, c.country\nFROM postgres.public.orders o\nLEFT JOIN postgres.public.customers c ON c.customer_id = o.customer_id'}});
    expect(yamlPreview?.textContent).toContain('select: |-');
    expect(yamlPreview?.textContent).toContain('LEFT JOIN postgres.public.customers');

    fireEvent.change(screen.getByLabelText('Domain'),{target:{value:'ai_rnd'}});
    fireEvent.click(screen.getByRole('button',{name:'Review generated YAML'}));

    expect(await screen.findByText('Move YAML file')).toBeInTheDocument();
    expect(screen.getByText(/objects\/orders\.yaml → domains\/ai_rnd\/objects\/orders\.yaml/))
      .toBeInTheDocument();
  });
});

describe('metric builder',()=>{
  afterEach(()=>vi.restoreAllMocks());

  it('exposes a Create metric action in the metric registry',async()=>{
    vi.stubGlobal('fetch',vi.fn(async(input:RequestInfo|URL)=>{
      const url=String(input);
      const body=url.endsWith('/model')?model:url.endsWith('/metrics')?[]:[];
      return {ok:true,status:200,json:async()=>body} as Response;
    }));

    render(<MemoryRouter initialEntries={['/metrics']}><App/></MemoryRouter>);

    const create=await screen.findByRole('link',{name:'Create metric'});
    expect(create).toHaveAttribute('href','/metrics/new');
  });

  it('requires selecting an existing base object before opening the metric form',async()=>{
    vi.stubGlobal('fetch',vi.fn(async(input:RequestInfo|URL)=>{
      const url=String(input);
      const body=url.endsWith('/model')?model
        :url.endsWith('/objects/retail.orders')?object
        :url.endsWith('/objects')?[object]
        :[];
      return {ok:true,status:200,json:async()=>body} as Response;
    }));

    render(<MemoryRouter initialEntries={['/metrics/new']}><App/></MemoryRouter>);

    expect(await screen.findByText('Choose a base object')).toBeInTheDocument();
    const continueButton=screen.getByRole('button',{name:'Continue to metric definition'});
    expect(continueButton).toBeDisabled();

    fireEvent.change(screen.getByLabelText('Base object'),{target:{value:'retail.orders'}});
    expect(continueButton).toBeEnabled();
    expect(screen.getByText('retail.orders')).toBeInTheDocument();
    fireEvent.click(continueButton);

    expect(await screen.findByText('Generated metric YAML')).toBeInTheDocument();
    expect(screen.getByText(/baseObject: retail.orders/)).toBeInTheDocument();
  });

  it('renders generated YAML beside the visual metric form',async()=>{
    vi.stubGlobal('fetch',vi.fn(async(input:RequestInfo|URL)=>{
      const url=String(input);
      const body=url.endsWith('/model')?model
        :url.endsWith('/objects/retail.orders')?object
        :[];
      return {ok:true,status:200,json:async()=>body} as Response;
    }));

    render(
      <MemoryRouter initialEntries={['/objects/retail.orders/metrics/new']}>
        <App/>
      </MemoryRouter>
    );

    expect(await screen.findByText('Generated metric YAML')).toBeInTheDocument();
    expect(screen.getByText(/kind: metric/)).toBeInTheDocument();
    expect(screen.getByText(/baseObject: retail.orders/)).toBeInTheDocument();
  });
});

describe('governance redaction',()=>{
  afterEach(()=>vi.restoreAllMocks());

  it('shows clear policy states for hidden physical lineage and YAML',async()=>{
    const redactedObject={...object,spec:{...object.spec,source:{}}};
    const redactedSource={file:'objects/orders.yaml',fields:[{dimension:'order_id',expression:'order_id'}]};
    vi.stubGlobal('fetch',vi.fn(async(input:RequestInfo|URL)=>{
      const url=String(input);
      const body=url.endsWith('/objects/retail.orders/source')?redactedSource
        :url.endsWith('/objects/retail.orders')?redactedObject
        :url.endsWith('/metrics')?[]
        :[];
      return {ok:true,status:200,json:async()=>body} as Response;
    }));

    render(<MemoryRouter initialEntries={['/objects/retail.orders']}><App/></MemoryRouter>);

    expect(await screen.findByText('Physical lineage hidden by policy.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button',{name:'yaml'}));
    expect(screen.getByText('Raw model YAML hidden by policy.')).toBeInTheDocument();
  });

  it('shows a policy state when compiled SQL is omitted',async()=>{
    vi.stubGlobal('fetch',vi.fn(async(input:RequestInfo|URL)=>{
      const url=String(input);
      const body=url.endsWith('/objects')?[object]
        :url.endsWith('/metrics')?[]
        :url.endsWith('/query')?{columns:[],rows:[],rowCount:0,elapsedMs:1,queryId:'query-1'}
        :[];
      return {ok:true,status:200,json:async()=>body} as Response;
    }));

    render(<MemoryRouter initialEntries={['/query']}><App/></MemoryRouter>);
    fireEvent.click((await screen.findByText('Run query')).closest('button')!);

    expect(await screen.findByText('Compiled Trino SQL hidden by policy.')).toBeInTheDocument();
  });
});
