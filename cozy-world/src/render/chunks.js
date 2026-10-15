// Глобальные правки шейдерных кусков three.js. Действуют на ВСЕ стандартные материалы (в том числе с патчами
// onBeforeCompile) и на ShaderMaterial с fog: true:
//  - туман по высоте: плотнее у земли и в низинах, утренняя дымка над озером, тёплое рассеяние у солнца;
//  - мягкая холодная заливка с теневой стороны (имитация отражённого света) и лёгкий отблеск неба;
//  - мокрые поверхности в зоне дождя (темнее и глаже);
//  - каскадные тени (ближний чёткий каскад + дальний) и плавное затухание теней на краю карты теней;
//  - направленные источники перебираются с конца, чтобы после цикла directLight был нулевым светом (солнцем):
//    на это опираются патчи растений (просвечиваемость), и это не ломается от дополнительных источников.
// Модуль должен быть импортирован до первой компиляции шейдеров (его импортирует atmosphere.js).

import * as THREE from 'three';
import { LAKE, WATER_Y, ZONES } from '../world/terrain-data.js';

const f = (v) => Number(v).toFixed(4);

// Общая униформа: clone() возвращает сам объект, поэтому UniformsUtils.clone/merge (через них материалы
// получают свои униформы) раздают всем материалам ОДИН и тот же объект - обновление видно везде сразу.
function shared(v) {
  v.clone = () => v;
  return v;
}

export const RENDER_UNIFORMS = {
  // x: плотность по расстоянию (как у FogExp2), y: плотность у «земли» тумана, z: спад с высотой (1/м), w: высота земли тумана
  cozyFogA: { value: shared(new THREE.Vector4(0, 0, 0.1, 0)) },
  // rgb: цвет рассеяния у солнца, a: сила
  cozyFogB: { value: shared(new THREE.Vector4(1, 0.85, 0.6, 0)) },
  // xyz: направление на солнце (мир), w: плотность утренней дымки над озером
  cozyFogC: { value: shared(new THREE.Vector4(0, 1, 0, 0)) },
  // xyz: направление «отражённого» света (видовые координаты камеры), w: сила
  cozyFill: { value: shared(new THREE.Vector4(0, 0, 1, 0)) },
  cozyFillColor: { value: shared(new THREE.Color(0.55, 0.65, 0.85)) },
  // rgb: цвет неба для отблеска, a: сила отблеска на гладком/мокром
  cozySky: { value: shared(new THREE.Vector4(0.5, 0.7, 1, 0)) },
  // x: мокрость в зоне дождя (0..1), y: время (для дымки), zw: -
  cozyWet: { value: shared(new THREE.Vector4(0, 0, 0, 0)) },
};

// ---------- туман ----------

const FOG_PARS_VERTEX = /* glsl */`
#ifdef USE_FOG
	varying float vFogDepth;
	varying vec3 vFogWorld;
#endif
`;

// мировая позиция из видовой: mv = R*w + t  =>  w = R^T * (mv - t)
const FOG_VERTEX = /* glsl */`
#ifdef USE_FOG
	vFogDepth = - mvPosition.z;
	vFogWorld = ( mvPosition.xyz - viewMatrix[ 3 ].xyz ) * mat3( viewMatrix );
#endif
`;

const FOG_PARS_FRAGMENT = /* glsl */`
#ifdef USE_FOG
	uniform vec3 fogColor;
	varying float vFogDepth;
	varying vec3 vFogWorld;
	#ifdef FOG_EXP2
		uniform float fogDensity;
	#else
		uniform float fogNear;
		uniform float fogFar;
	#endif
	uniform vec4 cozyFogA;
	uniform vec4 cozyFogB;
	uniform vec4 cozyFogC;
	uniform vec4 cozyWet;

	// оптическая толщина экспоненциального слоя density(y) = a * exp(-k * (y - base)) вдоль отрезка
	float cozyHeightOptical( float a, float k, float base, float camY, float pY, float dist ) {
		float h0 = clamp( camY - base, -30.0, 400.0 ), h1 = clamp( pY - base, -30.0, 400.0 );
		float e0 = exp( -k * h0 ), e1 = exp( -k * h1 );
		float dh = h1 - h0;
		float avg = abs( dh ) > 0.01 ? ( e0 - e1 ) / ( k * dh ) : e0;
		return a * dist * avg;
	}

	float cozyLakeMask( vec2 p ) {
		float d = distance( p, vec2( ${f(LAKE.x)}, ${f(LAKE.z)} ) );
		return 1.0 - smoothstep( ${f(LAKE.r * 0.7)}, ${f(LAKE.r * 1.7)}, d );
	}

	float cozyFogFactor( float legacyDensity ) {
		vec3 ray = vFogWorld - cameraPosition;
		#ifdef COZY_LITE
		float dl = ( cozyFogA.x > 0.0 ? cozyFogA.x : legacyDensity ) * length( ray );
		return 1.0 - exp( - dl * dl );
		#else
		float dist = length( ray );
		float dens = cozyFogA.x > 0.0 ? cozyFogA.x : legacyDensity;
		float dd = dens * dist;
		float optical = dd * dd;
		if ( cozyFogA.y > 0.0 ) optical += cozyHeightOptical( cozyFogA.y, max( cozyFogA.z, 0.005 ), cozyFogA.w, cameraPosition.y, vFogWorld.y, dist );
		// дымка над озером: низкий плотный слой, тянется полосами
		if ( cozyFogC.w > 0.0 ) {
			vec2 mid = cameraPosition.xz + ray.xz * 0.6;
			float lk = max( cozyLakeMask( vFogWorld.xz ), cozyLakeMask( mid ) );
			if ( lk > 0.0 ) {
				vec2 q = vFogWorld.xz * 0.09 + vec2( cozyWet.y * 0.05, cozyWet.y * 0.02 );
				float wisp = 0.55 + 0.45 * sin( q.x * 2.3 + sin( q.y * 1.7 ) * 1.6 ) * sin( q.y * 1.9 - q.x * 0.6 + 1.3 );
				optical += lk * wisp * cozyHeightOptical( cozyFogC.w, 1.1, ${f(WATER_Y)}, cameraPosition.y, vFogWorld.y, min( dist, ${f(LAKE.r * 3.0)} ) );
			}
		}
		return 1.0 - exp( -optical );
		#endif
	}

	// цвет дымки: к солнцу теплее и светлее (рассеяние вперёд)
	vec3 cozyFogTint() {
		#ifdef COZY_LITE
		return fogColor;
		#else
		vec3 dir = normalize( vFogWorld - cameraPosition );
		float s = max( dot( dir, cozyFogC.xyz ), 0.0 );
		return fogColor + cozyFogB.rgb * ( cozyFogB.a * ( s * s * s * s * 0.45 + pow( s, 24.0 ) * 0.9 ) );
		#endif
	}
#endif
`;

const FOG_FRAGMENT = /* glsl */`
#ifdef USE_FOG
	#ifdef FOG_EXP2
		float fogFactor = cozyFogFactor( fogDensity );
	#else
		float fogFactor = smoothstep( fogNear, fogFar, vFogDepth );
	#endif
	gl_FragColor.rgb = mix( gl_FragColor.rgb, cozyFogTint(), fogFactor );
#endif
`;

// ---------- свет ----------

const LIGHTS_PARS_EXTRA = /* glsl */`
uniform vec4 cozyFill;
uniform vec3 cozyFillColor;
uniform vec4 cozySky;
// мягкая «обёрнутая» заливка: светит и на перпендикулярные грани, гаснет только строго напротив
vec3 cozyFillIrradiance( const in vec3 n ) {
	float d = dot( n, cozyFill.xyz ) * 0.5 + 0.5;
	return cozyFillColor * ( cozyFill.w * d * d );
}
`;

const SHADOW_EXTRA = /* glsl */`
#if defined( USE_SHADOWMAP ) && NUM_DIR_LIGHT_SHADOWS > 0
	// 1 у края карты теней: там тень плавно уходит, а не обрывается линией
	float cozyShadowEdge( vec4 sc ) {
		vec2 e = abs( sc.xy / sc.w * 2.0 - 1.0 );
		return smoothstep( 0.86, 0.99, max( e.x, e.y ) );
	}
	#if NUM_DIR_LIGHT_SHADOWS == 2
	// два каскада одного солнца: [0] - ближний (чёткий), [1] - дальний
	float cozyCascadeShadow() {
		DirectionalLightShadow s0 = directionalLightShadows[ 0 ];
		DirectionalLightShadow s1 = directionalLightShadows[ 1 ];
		float k = cozyShadowEdge( vDirectionalShadowCoord[ 0 ] );
		float sh = 1.0;
		if ( k < 1.0 ) sh = getShadow( directionalShadowMap[ 0 ], s0.shadowMapSize, s0.shadowIntensity, s0.shadowBias, s0.shadowRadius, vDirectionalShadowCoord[ 0 ] );
		if ( k > 0.0 ) {
			float sf = getShadow( directionalShadowMap[ 1 ], s1.shadowMapSize, s1.shadowIntensity, s1.shadowBias, s1.shadowRadius, vDirectionalShadowCoord[ 1 ] );
			sf = mix( sf, 1.0, cozyShadowEdge( vDirectionalShadowCoord[ 1 ] ) );
			sh = mix( sh, sf, k );
		}
		return sh;
	}
	#endif
#endif
`;

// Цикл направленных источников: с конца к началу; каскадная тень для солнца; носитель дальнего каскада не светит.
const DIR_LOOP = /* glsl */`
#if ( NUM_DIR_LIGHTS > 0 ) && defined( RE_Direct )

	DirectionalLight directionalLight;
	#if defined( USE_SHADOWMAP ) && NUM_DIR_LIGHT_SHADOWS > 0
	DirectionalLightShadow directionalLightShadow;
	#endif

	#pragma unroll_loop_start
	for ( int i = 0; i < NUM_DIR_LIGHTS; i ++ ) {

		#define COZY_DIR ( NUM_DIR_LIGHTS - 1 - UNROLLED_LOOP_INDEX )
		#if !( defined( USE_SHADOWMAP ) && NUM_DIR_LIGHT_SHADOWS == 2 && COZY_DIR == 1 )

		directionalLight = directionalLights[ COZY_DIR ];
		getDirectionalLightInfo( directionalLight, directLight );

		#if defined( USE_SHADOWMAP ) && ( COZY_DIR < NUM_DIR_LIGHT_SHADOWS )
			#if NUM_DIR_LIGHT_SHADOWS == 2
			directLight.color *= ( directLight.visible && receiveShadow ) ? cozyCascadeShadow() : 1.0;
			#else
			directionalLightShadow = directionalLightShadows[ COZY_DIR ];
			directLight.color *= ( directLight.visible && receiveShadow ) ? mix( getShadow( directionalShadowMap[ COZY_DIR ], directionalLightShadow.shadowMapSize, directionalLightShadow.shadowIntensity, directionalLightShadow.shadowBias, directionalLightShadow.shadowRadius, vDirectionalShadowCoord[ COZY_DIR ] ), 1.0, cozyShadowEdge( vDirectionalShadowCoord[ COZY_DIR ] ) ) : 1.0;
			#endif
		#endif

		RE_Direct( directLight, geometryPosition, geometryNormal, geometryViewDir, geometryClearcoatNormal, material, reflectedLight );

		#endif
		#undef COZY_DIR

	}
	#pragma unroll_loop_end

#endif
`;

// В начало освещения: мокрость (темнее и глаже) - по мировой позиции, нужна дымка (USE_FOG).
const WET_BEGIN = /* glsl */`
#if defined( USE_FOG ) && !defined( COZY_LITE )
	{
		float cozyRainW = smoothstep( ${f(ZONES.rain.r)}, ${f(ZONES.rain.r * 0.45)}, distance( vFogWorld.xz, vec2( ${f(ZONES.rain.x)}, ${f(ZONES.rain.z)} ) ) ) * cozyWet.x;
		material.diffuseColor *= 1.0 - 0.28 * cozyRainW;
		#ifdef STANDARD
		material.roughness = mix( material.roughness, min( material.roughness, 0.32 ), cozyRainW );
		#endif
		cozyWetK = cozyRainW;
	}
#endif
`;

const HEMI_END = /* glsl */`
		irradiance += cozyFillIrradiance( geometryNormal );
`;

// отблеск неба: на гладком и мокром заметен, на шершавом - едва (свёртку делает RE_IndirectSpecular)
const SKY_SHEEN = /* glsl */`
#if defined( RE_IndirectSpecular ) && !defined( COZY_LITE )
	radiance += cozySky.rgb * ( cozySky.a + cozyWetK * 1.4 );
#endif
`;

function patchLights(src) {
  const a = src.indexOf('#if ( NUM_DIR_LIGHTS > 0 ) && defined( RE_Direct )');
  const b = src.indexOf('#if ( NUM_RECT_AREA_LIGHTS > 0 ) && defined( RE_Direct_RectArea )');
  if (a < 0 || b < 0 || b < a) return null;
  let out = `float cozyWetK = 0.0;\n${WET_BEGIN}\n${src.slice(0, a)}${DIR_LOOP}\n${src.slice(b)}`;
  // заливка - после полусферы
  const hemi = out.indexOf('getHemisphereLightIrradiance( hemisphereLights[ i ], geometryNormal );');
  if (hemi < 0) return null;
  const hemiEnd = out.indexOf('#endif', out.indexOf('#pragma unroll_loop_end', hemi));
  if (hemiEnd < 0) return null;
  out = `${out.slice(0, hemiEnd + 6)}\n${HEMI_END}${out.slice(hemiEnd + 6)}`;
  out += SKY_SHEEN;
  return out;
}

let applied = false;
export function applyRenderChunks() {
  if (applied) return;
  applied = true;
  const SC = THREE.ShaderChunk;
  SC.fog_pars_vertex = FOG_PARS_VERTEX;
  SC.fog_vertex = FOG_VERTEX;
  SC.fog_pars_fragment = FOG_PARS_FRAGMENT;
  SC.fog_fragment = FOG_FRAGMENT;
  const lights = patchLights(SC.lights_fragment_begin);
  if (lights) {
    SC.lights_fragment_begin = lights;
    SC.lights_pars_begin += LIGHTS_PARS_EXTRA;
    SC.shadowmap_pars_fragment += SHADOW_EXTRA;
  } else {
    console.warn('cozy: lights_fragment_begin не распознан - заливка и каскады теней выключены');
  }
  // униформы - во все встроенные шейдеры с туманом и в UniformsLib.fog (для ShaderMaterial)
  for (const k of Object.keys(THREE.ShaderLib)) {
    const u = THREE.ShaderLib[k].uniforms;
    if (u && u.fogColor) Object.assign(u, RENDER_UNIFORMS);
  }
  Object.assign(THREE.UniformsLib.fog, RENDER_UNIFORMS);
  // three копирует униформы при создании программы - лишние для шейдера просто не загружаются
}

applyRenderChunks();

// Лёгкий режим (low): туман только по расстоянию, без мокрых поверхностей и отблеска неба - шейдеры короче
// и дешевле на слабых устройствах. Вызывать до первой компиляции шейдеров.
let lite = false;
export function setRenderLite(on) {
  if (on === lite) return;
  lite = on;
  const SC = THREE.ShaderChunk;
  const mark = '#ifndef COZY_LITE\n#define COZY_LITE\n#endif\n';
  for (const k of ['fog_pars_fragment', 'lights_pars_begin']) {
    SC[k] = on ? mark + SC[k] : SC[k].replace(mark, '');
  }
}

export const cascadesSupported = () => THREE.ShaderChunk.lights_fragment_begin.includes('cozyCascadeShadow');
