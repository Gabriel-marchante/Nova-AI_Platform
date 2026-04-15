# 🤖 Documentación Técnica: Claudio (Orquestador MCP)

## 📌 Resumen del Proyecto
**Claudio** es un Orquestador de Inteligencia Artificial (Agente Autónomo) construido sobre **Quarkus**. Su principal característica es la implementación del protocolo **MCP (Model Context Protocol)**, lo que le permite conectarse dinámicamente a servidores externos, descubrir sus herramientas (Tools) en tiempo real, y utilizarlas mediante la IA para resolver consultas complejas de los usuarios.

El "cerebro" cognitivo está impulsado por **Google Gemini 2.5 Flash** a través de la librería **LangChain4j**.

## 🏗️ Arquitectura del Sistema

El sistema sigue una arquitectura de cliente-servidor reactiva orientada a eventos, dividida en 3 capas principales:

1. **Frontend (Interfaz de Usuario):** Una SPA (Single Page Application) en HTML/JS puro que permite al usuario gestionar las conexiones a los servidores MCP y chatear con el Agente.
2. **Backend (Orquestador Quarkus - Puerto 8081):** El núcleo del proyecto. Mantiene el estado de las sesiones, gestiona el bucle de razonamiento de la IA y enruta las peticiones de herramientas.
3. **Servidores MCP (Nodos Externos):** Microservicios independientes (ej. Python en puerto 8080, Quarkus en puerto 8082) que exponen capacidades mediante Server-Sent Events (SSE) y JSON-RPC.

## ⚙️ Componentes Principales (Código Base)

### 1. `McpController.java` (El Cerebro)
Controlador REST que actúa como puente entre la interfaz de usuario, los servidores MCP y el modelo de lenguaje de Google Gemini.
* **Gestión de Sesiones (`activeSessions`):** Utiliza un `ConcurrentHashMap` para mantener vivas las conexiones con múltiples servidores simultáneamente.
* **Bucle Agéntico (Agentic Loop):** Implementa un bucle de razonamiento de hasta 5 iteraciones. La IA puede decidir encadenar múltiples herramientas de diferentes servidores antes de emitir una respuesta final al usuario, manteniendo una "mochila" de contexto (memoria temporal).
* **Resolución Universal de URIs:** Capaz de conectarse a cualquier servidor interpretando rutas relativas o absolutas mediante `URI.resolve()`, evitando problemas de "Caché Envenenada".

### 2. `McpWeatherClient.java` (El Cliente Universal)
Interfaz reactiva basada en `@RegisterRestClient` de Quarkus.
* Agnóstica a las rutas: Utiliza `@Path("")` para adaptarse a cualquier endpoint configurado dinámicamente.
* Abre un canal unidireccional permanente vía `SERVER_SENT_EVENTS` (SSE) para recibir eventos del servidor.
* Utiliza peticiones `POST` síncronas para enviar comandos `JSON-RPC` (como `initialize`, `tools/list` y `tools/call`).

## 🔄 Flujo de Comunicación (El Protocolo MCP)

Cuando el usuario añade una nueva URL en el dashboard (ej. `http://localhost:8082/mcp/sse`), ocurre la siguiente magia por debajo:

1. **Handshake (GET):** Claudio abre la conexión SSE con la URL indicada.
2. **Enrutamiento (Evento `endpoint`):** El servidor responde indicando en qué URI secreta o dinámica espera recibir los POSTs (ej. `/mcp/message?sessionId=123`).
3. **Inicialización (POST `initialize`):** Claudio se presenta y negocia la versión del protocolo (`2024-11-05`).
4. **Descubrimiento (POST `tools/list`):** Claudio pide la lista de herramientas disponibles y las almacena en la caché de la sesión.

## 🧠 El Bucle de Razonamiento (Agentic Workflow)

Cuando el usuario hace una pregunta, Claudio no actúa como un simple pasador de mensajes. Sigue un patrón de **Agente ReAct (Reasoning and Acting)**:

1. **Evaluación:** Se inyecta la pregunta del usuario y el esquema JSON de TODAS las herramientas descubiertas en el *System Prompt* de Gemini.
2. **Decisión:** Si Gemini necesita datos, responde con una estructura JSON pidiendo ejecutar una acción (`{"action": "tool", "toolName": "buscar_nota"...}`).
3. **Ejecución:** El backend captura esta intención, enruta la petición al servidor MCP dueño de esa herramienta, y obtiene el resultado (`"Gandia"`).
4. **Iteración:** Se añade el resultado al historial de la conversación oculta y se le vuelve a preguntar a Gemini: *"Aquí tienes el dato, ¿qué hacemos ahora?"*.
5. **Resolución:** Si Gemini determina que necesita usar otra herramienta (ej. el clima de Gandia), repite el ciclo. Si ya tiene todo, envía la respuesta final en lenguaje natural (`{"action": "reply", "message": "..."}`).

## 🚀 Despliegue y Configuración

### Prerrequisitos
* Java 17+
* Maven
* Una API Key de Google Gemini Studio.

### Variables de Entorno (`application.properties`)
Debe configurarse la clave de la IA para que el orquestador pueda pensar:
```properties
gemini.api.key=AIzaSyTuClaveDeGoogleAqui...

Ejecución en Modo Desarrollo
Para arrancar el orquestador con Live Reload:

Bash
./mvnw clean quarkus:dev
El panel de control (Dashboard) estará disponible en: http://localhost:8081