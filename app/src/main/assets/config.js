// Dirección de la guía actualizada. La tarea programada reescribe data/partidos.json
// en el repositorio de GitHub los martes y viernes, y la app la descarga de aquí.
window.DATOS_URL = "https://raw.githubusercontent.com/Alexample/dondeloechan/main/data/partidos.json";

// Comentarios (Giscus, guardados en GitHub Discussions). Sin categoryId no se muestran.
window.GISCUS = {
  repo: "Alexample/dondeloechan",
  repoId: "R_kgDOVRZ6Lg",
  category: "Announcements",
  categoryId: "DIC_kwDOVRZ6Ls4DJM5l"
};

// Marcadores de football-data.org (plan gratuito: Primera y Champions, con unos minutos de retraso).
window.MARCADORES = {
  clave: "2e0b52f50c68465084f4da6f3c3c7613",
  competiciones: { 1: "PD", 4: "CL" }
};
