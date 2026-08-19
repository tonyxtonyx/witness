import {afterEach,describe,expect,it} from 'vitest';
// @ts-expect-error Vitest provides Node at test runtime; the browser build intentionally has no Node types.
import {readFileSync} from 'node:fs';

const styles=readFileSync('src/styles.css','utf8');

afterEach(()=>{document.head.innerHTML='';document.body.innerHTML=''});

function installStyles(){
  expect(styles).toContain('.check-label');
  const style=document.createElement('style');
  style.textContent=styles.replace(/^@import[^\n]*\n/,'');
  document.head.append(style);
}

describe('form control sizing',()=>{
  it('keeps checkbox and radio controls compact inside panels and wizards',()=>{
    installStyles();
    document.body.innerHTML='<section class="panel"><label class="check-label"><input type="checkbox">Enabled</label><input type="text"></section><section class="wizard"><label class="check-label"><input type="radio">Choice</label></section>';
    const checkbox=document.querySelector<HTMLInputElement>('input[type="checkbox"]')!;
    const radio=document.querySelector<HTMLInputElement>('input[type="radio"]')!;
    const text=document.querySelector<HTMLInputElement>('input[type="text"]')!;

    for(const control of[checkbox,radio]){
      const computed=getComputedStyle(control);
      expect(computed.width).toBe('14px');
      expect(computed.height).toBe('14px');
      expect(computed.minHeight).toBe('0');
      expect(computed.padding).toBe('0px');
      expect(computed.margin).toBe('0px');
    }
    expect(getComputedStyle(text).width).toBe('100%');
    expect(getComputedStyle(text).minHeight).toBe('37px');
  });

  it('keeps checkbox labels inline in grid, admin flex, and builder flex contexts',()=>{
    installStyles();
    document.body.innerHTML='<label class="check-label" id="grid"><input type="checkbox">Grid</label><fieldset class="admin-checks"><label class="check-label" id="admin"><input type="checkbox">Admin</label></fieldset><div class="builder-checks"><label class="check-label" id="builder"><input type="checkbox">Builder</label></div>';

    expect(getComputedStyle(document.querySelector('#grid')!).display).toBe('grid');
    expect(getComputedStyle(document.querySelector('#admin')!).display).toBe('flex');
    expect(getComputedStyle(document.querySelector('#builder')!).display).toBe('flex');
    for(const label of document.querySelectorAll('.check-label')){
      expect(getComputedStyle(label).alignItems).toBe('center');
    }
  });
});
